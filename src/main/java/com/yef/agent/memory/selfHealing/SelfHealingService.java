package com.yef.agent.memory.selfHealing;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.event.EpistemicEventType;
import com.yef.agent.memory.selfHealing.builder.SelfCorrectionContextBuilder;
import com.yef.agent.memory.selfHealing.eum.ClaimRelationType;
import com.yef.agent.memory.selfHealing.mutation.ConfidenceAdjust;
import com.yef.agent.memory.selfHealing.mutation.RelationAttach;
import com.yef.agent.memory.selfHealing.mutation.StatusOverride;
import com.yef.agent.memory.selfHealing.repository.ClaimRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import static org.neo4j.driver.Values.parameters;

@Component
public class SelfHealingService {

    private final Driver driver;
    private final KeyCodec keyCodec;
    private final SelfCorrectionContextBuilder contextBuilder;
    private final SelfCorrectionResolver resolver;
    private final ClaimRepository claimRepository;

    public SelfHealingService(Driver driver,
                              KeyCodec keyCodec,
                              SelfCorrectionContextBuilder contextBuilder,
                              SelfCorrectionResolver resolver,
                              ClaimRepository claimRepository) {
        this.driver = driver;
        this.keyCodec = keyCodec;
        this.contextBuilder = contextBuilder;
        this.resolver = resolver;
        this.claimRepository = claimRepository;
    }


    public void handle(EpistemicEvent event) {
        if (!shouldHeal(event)) return;

        SelfCorrectionContext ctx = contextBuilder.from(event);

        SelfCorrectionResult result = resolver.resolve(ctx.newClaim(), ctx.conflictedClaims());

        if (!result.triggered()) return;

        apply(ctx.userId(), result);
    }


    //是否修复
    private boolean shouldHeal(EpistemicEvent event) {
        // v1：只处理用户反驳
        if (event.type() != EpistemicEventType.OPPOSE) {
            return false;
        }

        // 必须有 triggerKey（指向某条 claim）
        if (event.triggerKey() == null) {
            return false;
        }

        return true;
    }


    /**
     * 应用 Self-Healing 计算得到的一组认知变更（ClaimMutation）。
     *
     * <p>
     * 这是「自我修复」真正产生副作用的唯一入口：
     * SelfCorrectionResolver 只负责“裁决应该怎么改”，
     * 本方法负责“把裁决结果落地到图谱（Neo4j）中”。
     * </p>
     *
     * <p>
     * 当前 v1 阶段，系统允许的认知变更类型是严格受控的三种：
     * </p>
     * <ul>
     *   <li>{@link ConfidenceAdjust}：调整某条 Claim 的置信度（不改变其真假立场）</li>
     *   <li>{@link StatusOverride}：强制覆盖 Claim 的 epistemicStatus（如 CONFIRMED → DENIED）</li>
     *   <li>{@link RelationAttach}：在两条 Claim 之间建立认知关系（如 OVERRIDDEN_BY / OPPOSES）</li>
     * </ul>
     *
     * <p>
     * 设计原则：
     * <ul>
     *   <li>所有 mutation 都必须显式、可审计、可回放</li>
     *   <li>禁止在此方法中做任何“隐式判断”或“自动推理”</li>
     *   <li>Mutation 只是“变更指令”，不携带业务逻辑</li>
     * </ul>
     * </p>
     *
     * <p>
     * 注意：
     * <ul>
     *   <li>claimId 实际上是 Claim 的 evidenceKey（五元组 key）</li>
     *   <li>本方法假定 resolver 已保证 mutation 的合法性</li>
     * </ul>
     * </p>
     */
    private void apply(String userId, SelfCorrectionResult r) {
        for (var m : r.mutations()) {

            if (m instanceof ConfidenceAdjust ca) {
                applyConfidenceAdjust(userId, ca.claimId(), ca);

            } else if (m instanceof StatusOverride so) {
                applyStatusOverride(userId, so.claimId(), so.newStatus().name());

            } else if (m instanceof RelationAttach ra) {
                applyRelationAttach(userId, ra.fromClaimId(), ra.toClaimId(), ra.type());
            }
        }
    }


    private void applyConfidenceAdjust(String userId,
                                       String claimKey,
                                       ConfidenceAdjust adjust) {
        KeyCodec.DecodedKey k = keyCodec.decode(claimKey);
        // 1. 从图谱中读取当前 confidence

        double oldConfidence = loadConfidenceFromGraph(userId, k);
        // 2. 计算新值
        double newConfidence = adjust.toConfidence(oldConfidence);

        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
                  subjectId:$sid, predicate:$pred, objectId:$oid, quantifier:$q, polarity:$pol, legacy:false
                })
                SET c.confidence = $conf,
                    c.updatedAt = datetime()
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "sid", k.subjectId(),
                    "pred", k.predicate().name(),
                    "oid", k.objectId(),
                    "q", k.quantifier().name(),
                    "pol", k.polarity(),
                    "conf", newConfidence
            )).consume());
        }
    }

    private void applyStatusOverride(String userId, String claimKey, String toStatus) {
        var k = keyCodec.decode(claimKey);

        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
                  subjectId:$sid, predicate:$pred, objectId:$oid, quantifier:$q, polarity:$pol, legacy:false
                })
                SET c.epistemicStatus = $to,
                    c.updatedAt = datetime()
                WITH c
                MERGE (s:EpistemicStatus {name:$to})
                OPTIONAL MATCH (c)-[old:CURRENT_STATUS]->(:EpistemicStatus)
                DELETE old
                MERGE (c)-[:CURRENT_STATUS]->(s)
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "sid", k.subjectId(),
                    "pred", k.predicate().name(),
                    "oid", k.objectId(),
                    "q", k.quantifier().name(),
                    "pol", k.polarity(),
                    "to", toStatus
            )).consume());
        }
    }

    private void applyRelationAttach(String userId, String fromKey,
                                     String toKey,
                                     ClaimRelationType relType) {
        var a = keyCodec.decode(fromKey);
        var b = keyCodec.decode(toKey);

        // relType 先只允许 OVERRIDDEN_BY，避免写乱
        if (!"OVERRIDDEN_BY".equals(relType.name())) return;

        String cypher = """
                MATCH (u:User {id:$uid})
                MATCH (u)-[:ASSERTS]->(from:Claim {
                  subjectId:$asid, predicate:$apred, objectId:$aoid, quantifier:$aq, polarity:$apol, legacy:false
                })
                MATCH (u)-[:ASSERTS]->(to:Claim {
                  subjectId:$bsid, predicate:$bpred, objectId:$boid, quantifier:$bq, polarity:$bpol, legacy:false
                })
                MERGE (from)-[:OVERRIDDEN_BY]->(to)
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "asid", a.subjectId(),
                    "apred", a.predicate().name(),
                    "aoid", a.objectId(),
                    "aq", a.quantifier().name(),
                    "apol", a.polarity(),
                    "bsid", b.subjectId(),
                    "bpred", b.predicate().name(),
                    "boid", b.objectId(),
                    "bq", b.quantifier().name(),
                    "bpol", b.polarity()
            )).consume());
        }
    }


    private double loadConfidenceFromGraph(String userId, KeyCodec.DecodedKey k) {
        String cypher = """
                MATCH (u:User {id: $uid})-[:ASSERTS]->(c:Claim {
                    subjectId: $sid,
                    predicate: $pred,
                    objectId: $oid,
                    quantifier: $q,
                    polarity: $pol,
                    legacy: false
                })
                RETURN c.confidence AS confidence
                LIMIT 1
                """;

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        cypher,
                        parameters(
                                "uid", userId,
                                "sid", k.subjectId(),
                                "pred", k.predicate().name(),
                                "oid", k.objectId(),
                                "q", k.quantifier().name(),
                                "pol", k.polarity()
                        )
                );

                if (!result.hasNext()) {
                    throw new IllegalStateException(
                            "Claim not found for key=" + k
                    );
                }

                var record = result.next();
                return record.get("confidence").asDouble();
            });
        }
    }


}