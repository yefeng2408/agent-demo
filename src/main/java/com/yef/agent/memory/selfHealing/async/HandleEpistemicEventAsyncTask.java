package com.yef.agent.memory.selfHealing.async;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.event.EpistemicEventType;
import com.yef.agent.memory.selfHealing.*;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.util.List;
import static org.neo4j.driver.Values.parameters;

/**
 * HandleEpistemicEventAsyncTask 做的不是确定状态，而是“可解释、可演化”，像 StatusTransition 一样
 *  Self-Healing 的核心目的不是“消灭冲突”，而是：
 * 	•	把“冲突”显式化
 * 	•	把“为什么会冲突”结构化
 * 	•	把“哪一条更可能成为主导”留下 hint
 * 	•	把“系统是如何一步步倾向某个结论的”记录下来
 *
 * “什么时候不触发自我修复”——————最重要（先写死一版规则）
 *  不触发自我修复的情况如下：
 * 	•	不是 OPPOSE（support/neutral 不修复）
 * 	•	slot 内 claim 少于 2 条（没有冲突）
 * 	•	newClaim 和 oldClaim polarity 相同（不是互斥）
 * 	•	newClaim 还是 UNKNOWN 且 confidence 太低（比如 < 0.25）——避免一句话就乱修
 * 	•	oldClaim 仍然 CONFIRMED 且 confidence 极高（比如 > 0.90）——避免瞬间推翻
 *
 */

@Component
public class HandleEpistemicEventAsyncTask {

    private final KeyCodec keyCodec;
    private final ClaimSlotQuery claimSlotQuery;
    private final SelfCorrectionResolver selfCorrectionResolver;
    private final Driver driver;

    public HandleEpistemicEventAsyncTask(KeyCodec keyCodec,
                                         ClaimSlotQuery claimSlotQuery,
                                         SelfCorrectionResolver selfCorrectionResolver,
                                         Driver driver) {
        this.keyCodec = keyCodec;
        this.claimSlotQuery = claimSlotQuery;
        this.selfCorrectionResolver = selfCorrectionResolver;
        this.driver = driver;
    }

    /** ✅ 异步消费：persistEpistemicEvent() 之后调用它 */
    @Async
    public void handle(String userId, EpistemicEvent event) {

        // 0) 不关心 SUPPORT（v1），只修复 OPPOSE
        if (event.type() != EpistemicEventType.OPPOSE) {
            return;
        }

        // 1) triggerKey -> newClaim 的 slot
        var decoded = keyCodec.decode(event.triggerKey());

        // 2) 查同 slot 所有 candidates（true/false 都要）
        List<ClaimEvidence> slotClaims = claimSlotQuery.findAllInSlot(
                userId,
                decoded.subjectId(),
                decoded.predicate(),
                decoded.objectId(),
                decoded.quantifier()
        );

        if (slotClaims.size() < 2) {
            return; // 没有冲突，不修
        }


        // 3) 选出 newClaim（就是 trigger polarity 对应那条）以及 existing（其余）
        ClaimEvidence newClaim = slotClaims.stream()
                .filter(c -> c.polarity() == decoded.polarity())
                .findFirst()
                .orElse(null);

        if (newClaim == null) {
            // 说明 triggerKey 指向的 claim 没落库（不应该），直接退出
            return;
        }

        List<ClaimEvidence> existingClaims = slotClaims.stream()
                .filter(c -> c.polarity() != decoded.polarity())
                .toList();

        if (existingClaims.isEmpty()) {
            return;
        }

        // 4) v1：触发条件（保守）
        if (!shouldTrigger(newClaim, existingClaims)) {
            return;
        }

        // 5) resolver 生成 mutations
        SelfCorrectionResult r = selfCorrectionResolver.resolve(newClaim, slotClaims);

        if (!r.triggered()) {
            return;
        }

        // 6) 应用 mutations（写 Neo4j）
        applyMutations(userId, r);
    }


    private boolean shouldTrigger(ClaimEvidence newClaim, List<ClaimEvidence> existingClaims) {
        double newConf = newClaim.confidence();
        EpistemicStatus newStatus = newClaim.epistemicStatus() == null ? EpistemicStatus.UNKNOWN : newClaim.epistemicStatus();

        // v1：太弱不修
        if (newConf < 0.25 && newStatus == EpistemicStatus.UNKNOWN) return false;

        // 如果旧方仍然很强（极高置信），也先不修（避免一句话推翻）
        boolean oldVeryStrong = existingClaims.stream()
                .anyMatch(o -> o.confidence() > 0.90 && o.epistemicStatus() == EpistemicStatus.CONFIRMED);

        return !oldVeryStrong;
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
    private void applyMutations(String userId, SelfCorrectionResult r) {
        // v1：我们只实现你现在 resolver 里已经定义的三种：
        // - ConfidenceAdjust(claimId, toConfidence)
        // - StatusOverride(claimId, status)
        // - RelationAttach(fromId, toId, "OVERRIDDEN_BY")

        // 注意：你 resolver 里 claimId 就是 evidenceKey（五元组 key）
        for (var m : r.mutations()) {

            // 1. 置信度调整（数值层面的衰减 / 提升）
            if (m instanceof ConfidenceAdjust ca) {

                applyConfidenceAdjust(userId, ca.claimId(), ca);

                // 2. 认知状态强制覆盖（如被用户明确否认）
            } else if (m instanceof StatusOverride so) {
                applyStatusOverride(userId, so.claimId(), so.newStatus().name());

                // 3. 认知关系建立（用于可解释性与状态演化路径）
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