package com.yef.agent.service;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.memory.EpistemicStateMachine;
import com.yef.agent.memory.EpistemicStatus;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import java.util.List;
import static org.neo4j.driver.Values.parameters;

/**
 * ⚠️ IMPORTANT:
 * Answer / LLM output MUST NEVER be written as fact.
 * Only Claim with evidence + confidence evolution is allowed.
 */
@Slf4j
@Component
public class ClaimConfidenceService {

    private final Driver driver;
    private final EpistemicStateMachine stateMachine; // 构造器注入

    // 支持强度 & 反证强度
    private static final double SUPPORT_INC = 0.10;
    private static final double COUNTER_DEC = 0.12;

    public ClaimConfidenceService(Driver driver,EpistemicStateMachine stateMachine) {
        this.driver = driver;
        this.stateMachine = stateMachine;
    }


    public void applyAnswer(String userId, AnswerResult result) {
        log.info("applyAnswer CALLED for user={}, relation={}", userId, result.relation());
        if (result == null || !result.answered() || result.relation() == null) return;

        // 1) 把 relation 当作候选 claim
        ExtractedRelation r = result.relation();
        if (r.isLegacy()) {
            // 不参与 confidence 演化
            return;
        }
        // 2) upsert 同向 claim（支持它）
        upsertAndSupport(userId, r, SUPPORT_INC);
        // 3) 如果你愿意做“反证打压”：打压 polarity 相反的 claim
        downscaleOpposite(userId, r, COUNTER_DEC);
        // 3.9.2.3 决策点：可能触发 epistemicStatus 状态迁移
        maybeTransition(userId, r, "answer_loop");

        // 4) 把 Answer 证据链挂上（如果你已经写 Answer 节点了）
        // linkAnswerSupportsClaim(...)
    }

    /**
     *  更新认知状态
     * 	•	创建 / 更新 Claim
     * 	•	调整 confidence
     * 	•	增加 supportCount
     * 	•	触发对立 Claim 的衰减
     * 	•	属于 记忆系统的“状态演化引擎”
     * @param userId
     * @param r
     * @param inc
     */
    private void upsertAndSupport(String userId, ExtractedRelation r, double inc) {
        String cypher = """
                MERGE (u:User {id:$uid})
                MERGE (u)-[:ASSERTS]->(c:Claim {
                  subjectId:$sid,
                  predicate:$pred,
                  objectId:$oid,
                  quantifier:$q,
                  polarity:$pol,
                  legacy:$legacy
                })
                ON CREATE SET
                  c.confidence      = $baseConf,
                  c.supportCount    = 1,
                  c.source          = $source,
                  c.batch           = $batch,
                  c.generation      = $generation,
                  c.epistemicStatus = coalesce(c.epistemicStatus,'UNKNOWN'),
                  c.createdAt       = datetime(),
                  c.updatedAt       = datetime(),
                  c.lastSupportedAt = datetime()
                ON MATCH SET
                  c.confidence = CASE
                    WHEN c.confidence IS NULL THEN $baseConf
                    WHEN (c.confidence * 0.995) + $inc > 0.99 THEN 0.99
                    ELSE (c.confidence * 0.995) + $inc
                  END,
                  c.supportCount    = coalesce(c.supportCount, 0) + 1,
                  c.updatedAt       = datetime(),
                  c.lastSupportedAt = datetime()
                WITH c
                MERGE (s:EpistemicStatus {name: coalesce(c.epistemicStatus,'UNKNOWN')})
                OPTIONAL MATCH (c)-[old:CURRENT_STATUS]->(:EpistemicStatus)
                FOREACH (_ IN CASE WHEN old IS NULL THEN [] ELSE [1] END | DELETE old)
                MERGE (c)-[:CURRENT_STATUS]->(s)
                """;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "sid", r.subjectId(),
                    "pred", r.predicateType().name(),
                    "oid", r.objectId(),
                    "q", r.quantifier().name(),
                    "pol", r.polarity(),
                    "status", r.polarity() ? "CONFIRMED" : "DENIED",

                    "generation", r.generation().name(),
                    "legacy", r.generation().isLegacy(),

                    "inc", inc,
                    "baseConf", Math.min(r.confidence(), 0.7),
                    "source", r.source().name(),
                    "batch", "answer-loop"
            )).consume());
        }
    }

    //当用户支持一个 Claim 时，对极性相反的 Claim进行信任衰减
    private void downscaleOpposite(String userId, ExtractedRelation r, double dec) {
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim)
        WHERE c.subjectId=$sid
          AND c.predicate=$pred
          AND c.objectId=$oid
          AND c.quantifier=$q
          AND c.polarity <> $pol
          AND c.legacy = false
        SET c.confidence = CASE
            WHEN ((coalesce(c.confidence, 0.5) * 0.995) - $dec) < 0.0 THEN 0.0
            ELSE ((coalesce(c.confidence, 0.5) * 0.995) - $dec)
            END,
            c.updatedAt = datetime()
        """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "sid", r.subjectId(),
                    "pred", r.predicateType().name(),
                    "oid", r.objectId(),
                    "q", r.quantifier().name(),
                    "pol", r.polarity(),
                    "dec", dec
            )).consume());
        }
    }



    /**
     * 3.9.2.3 状态迁移决策点（最小闭环）：
     * - 只作用于 legacy=false
     * - 读当前 Claim 状态
     * - 计算 nextStatus
     * - 若发生变化：写入 StatusTransition + 更新 CURRENT_STATUS
     */
    private void maybeTransition(String userId, ExtractedRelation r, String reason) {
        if (r == null || r.isLegacy()) return;

        // 1) 读当前 claim 的状态快照（如果不存在，直接返回）
        ClaimSnapshot cur = readClaimSnapshot(userId, r);
        if (cur == null) return;

        // 2) 计算 next status（你可以按自己规则改阈值）
        String next = computeNextStatus(cur);

        // 3) 没变化就不写（避免每次都产生 transition 垃圾）
        String from = normalizeStatus(cur.epistemicStatus);
        String to = normalizeStatus(next);
        if (from.equals(to)) return;

        // 4) 写入迁移：更新 claim + 记录 transition + 维护 CURRENT_STATUS
        writeTransition(userId, r, from, to, reason, cur.confidence, cur.supportCount);
    }

    /** 读 Claim 当前快照（legacy=false + 精准 key） */
    private ClaimSnapshot readClaimSnapshot(String userId, ExtractedRelation r) {
        String cypher = """
                        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
                          subjectId:$sid,
                          predicate:$pred,
                          objectId:$oid,
                          quantifier:$q,
                          polarity:$pol,
                          legacy:false
                        })
                        OPTIONAL MATCH (c)-[:CURRENT_STATUS]->(cs:EpistemicStatus)
                        RETURN
                          coalesce(c.epistemicStatus,'UNKNOWN') AS epistemicStatus,
                          coalesce(cs.name,'UNKNOWN') AS currentStatus,
                          coalesce(c.confidence, 0.5) AS confidence,
                          coalesce(c.supportCount, 0) AS supportCount
                        LIMIT 1
                        """;

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var res = tx.run(cypher, parameters(
                        "uid", userId,
                        "sid", r.subjectId(),
                        "pred", r.predicateType().name(),
                        "oid", r.objectId(),
                        "q", r.quantifier().name(),
                        "pol", r.polarity()
                ));
                if (!res.hasNext()) return null;
                var rec = res.next();
                return new ClaimSnapshot(
                        rec.get("epistemicStatus").asString(),
                        rec.get("currentStatus").asString(),
                        rec.get("confidence").asDouble(),
                        rec.get("supportCount").asInt()
                );
            });
        }
    }

    /**
     * 计算 nextStatus（最小规则）
     *
     * 你可以改成你自己的状态机：
     * - UNKNOWN / HYPOTHETICAL / CONFIRMED / DENIED / DISPUTED
     *
     * 这里给一个可用的“阈值版”：
     * - supportCount >= 2 且 confidence >= 0.85 -> CONFIRMED
     * - confidence <= 0.20 -> DISPUTED
     * - 其他 -> HYPOTHETICAL
     *
     * 注意：当前 status 的定义里，polarity=true/false 已经把方向编码进 claim 了，
     * 所以 epistemicStatus 可以只描述“可信度阶段”，不用再区分正/反。
     */
    private String computeNextStatus(ClaimSnapshot cur) {
        double conf = cur.confidence;
        int sc = cur.supportCount;

        if (sc >= 2 && conf >= 0.85) return "CONFIRMED";
        if (conf <= 0.20) return "DISPUTED";
        // 如果你更喜欢 UNKNOWN 起步，也可以：sc==0 -> UNKNOWN
        return "HYPOTHETICAL";
    }

    /** 写入迁移记录 + CURRENT_STATUS */
    private void writeTransition(
            String userId,
            ExtractedRelation r,
            String from,
            String to,
            String reason,
            double confidence,
            int supportCount) {
        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
                  subjectId:$sid,
                  predicate:$pred,
                  objectId:$oid,
                  quantifier:$q,
                  polarity:$pol,
                  legacy:false
                })
                WITH c, $from AS from, $to AS to
                // 更新 claim 的当前状态字段
                SET c.epistemicStatus = to,
                    c.updatedAt = datetime()
                // 状态节点
                MERGE (fromS:EpistemicStatus {name: from})
                MERGE (toS:EpistemicStatus   {name: to})
                // 迁移事件节点
                CREATE (t:StatusTransition {
                  id: randomUUID(),
                  at: datetime(),
                  reason: $reason,
                  from: from,
                  to: to,
                  confidence: $confidence,
                  supportCount: $supportCount
                })
                MERGE (c)-[:HAS_TRANSITION]->(t)
                MERGE (t)-[:FROM]->(fromS)
                MERGE (t)-[:TO]->(toS)
                // CURRENT_STATUS 只保留一条
                WITH c, toS
                OPTIONAL MATCH (c)-[old:CURRENT_STATUS]->(:EpistemicStatus)
                DELETE old
                MERGE (c)-[:CURRENT_STATUS]->(toS)
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "sid", r.subjectId(),
                    "pred", r.predicateType().name(),
                    "oid", r.objectId(),
                    "q", r.quantifier().name(),
                    "pol", r.polarity(),
                    "from", from,
                    "to", to,
                    "reason", reason,
                    "confidence", confidence,
                    "supportCount", supportCount
            )).consume());
        }
    }

    /** 统一空值/非法值 */
    private String normalizeStatus(String s) {
        if (s == null || s.isBlank()) return "UNKNOWN";
        return s.trim();
    }

    /** 最小快照结构 */
    private static class ClaimSnapshot {
        final String epistemicStatus;
        final String currentStatus;
        final double confidence;
        final int supportCount;

        ClaimSnapshot(String epistemicStatus, String currentStatus, double confidence, int supportCount) {
            this.epistemicStatus = epistemicStatus;
            this.currentStatus = currentStatus;
            this.confidence = confidence;
            this.supportCount = supportCount;
        }
    }

    // 你可以在 ClaimConfidenceService 里加一个内部 key record
    private record ClaimKey(String subjectId, String predicate, String objectId, String quantifier, boolean polarity) {}


    private void maybeTransitionStatus(String userId, ExtractedRelation r, String reason) {
        ClaimKey key = new ClaimKey(
                r.subjectId(),
                r.predicateType().name(),
                r.objectId(),
                r.quantifier().name(),
                r.polarity()
        );
        maybeTransitionStatus(userId, key, reason);
    }


    private void maybeTransitionStatus(String userId, ClaimKey key, String reason) {
        String readCypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
          subjectId:$sid, predicate:$pred, objectId:$oid,
          quantifier:$q, polarity:$pol, legacy:false
        })
        RETURN
          coalesce(c.epistemicStatus,'UNKNOWN') AS st,
          coalesce(c.confidence,0.5)          AS conf,
          coalesce(c.supportCount,0)          AS sc,
          c.polarity                          AS pol
        """;
        String writeCypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
          subjectId:$sid, predicate:$pred, objectId:$oid,
          quantifier:$q, polarity:$pol, legacy:false
        })
        WITH c, coalesce(c.epistemicStatus,'UNKNOWN') AS from
        SET c.epistemicStatus = $to,
            c.updatedAt = datetime()
        WITH c, from
        MERGE (fromS:EpistemicStatus {name: from})
        MERGE (toS:EpistemicStatus   {name: $to})
        CREATE (t:StatusTransition {
          id: randomUUID(),
          at: datetime(),
          reason: $reason,
          from: from,
          to: $to,
          confidence: c.confidence,
          supportCount: c.supportCount
        })
        MERGE (c)-[:HAS_TRANSITION]->(t)
        MERGE (t)-[:FROM]->(fromS)
        MERGE (t)-[:TO]->(toS)
        WITH c, toS
        OPTIONAL MATCH (c)-[old:CURRENT_STATUS]->(:EpistemicStatus)
        FOREACH (_ IN CASE WHEN old IS NULL THEN [] ELSE [1] END | DELETE old)
        MERGE (c)-[:CURRENT_STATUS]->(toS)
        """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                var params = parameters(
                        "uid", userId,
                        "sid", key.subjectId(),
                        "pred", key.predicate(),
                        "oid", key.objectId(),
                        "q", key.quantifier(),
                        "pol", key.polarity()
                );

                var rec = tx.run(readCypher, params).single();

                EpistemicStatus from = EpistemicStatus.valueOf(rec.get("st").asString());
                double conf = rec.get("conf").asDouble();
                long sc = rec.get("sc").asLong();
                boolean pol = rec.get("pol").asBoolean();

                EpistemicStatus to = stateMachine.nextGraph(from, pol, conf, sc);
                if (to == from) return null;

                tx.run(writeCypher, parameters(
                        "uid", userId,
                        "sid", key.subjectId(),
                        "pred", key.predicate(),
                        "oid", key.objectId(),
                        "q", key.quantifier(),
                        "pol", key.polarity(),
                        "to", to.name(),
                        "reason", reason == null ? "threshold_crossed" : reason
                )).consume();
                return null;
            });
        }
    }


}