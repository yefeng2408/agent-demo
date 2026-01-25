package com.yef.agent.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.answer.Neo4jGraphAnswerer;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.SemanticRelation;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.DeltaDirection;
import com.yef.agent.memory.EpistemicStateMachine;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.event.OpposeEvent;
import com.yef.agent.memory.event.SupportEvent;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.micrometer.core.instrument.config.MeterFilterReply.NEUTRAL;
import static java.io.ObjectInputFilter.Status.REJECTED;
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
    private final EpistemicStateMachine stateMachine;

    // 支持强度 & 反证强度
    private static final double SUPPORT_INC = 0.10;
    private static final double COUNTER_DEC = 0.12;

    public ClaimConfidenceService(Driver driver,EpistemicStateMachine stateMachine) {
        this.driver = driver;
        this.stateMachine = stateMachine;
    }


    public void applyAnswer(String userId, AnswerResult result,ExtractedRelation newExtractedRelation) {
        log.info("applyAnswer CALLED for user={}, relation={}", userId, result.relation());
        if (result == null || !result.answered() || result.relation() == null) return;

        // 1) 把 relation 当作候选 claim
        ExtractedRelation r = result.relation();
        if (r.isLegacy()) {
            // 不参与 confidence 演化
            return;
        }
        // 1) ⭐关键新增：确保本次 polarity 的 claim 存在
        ensureClaimSlotExists(userId, newExtractedRelation);

        // 2. find conflicting claims (NO minConf!)
        List<ClaimEvidence> related = queryClaimsForEvolution(userId, r);

        // 3. update confidence & maybeTransition
        evolveClaims(userId, result, newExtractedRelation);

        // 3.9.2.3 决策点：可能触发 epistemicStatus 状态迁移
        //maybeTransition(userId, r, "answer_loop");
       /* if (nextStatus == EpistemicStatus.CONFIRMED) {
            reconcileOppositeClaims(userId, r, claimId);
        }*/
        // 4) 把 Answer 证据链挂上（如果你已经写 Answer 节点了）
        // linkAnswerSupportsClaim(...)
    }



    /**
     * 演化过程
     *
     * @param userId
     * @param result 根据Neo4jGraphAnswerer.queryClaims查询且裁决后得到的claim
     * @param newExtractedRelation 根据当前msg抽取得到的 ExtractedRelation
     */
    // 第一版：只处理 SUPPORT / OPPOSE / NEUTRAL
    private void evolveClaims(String userId, AnswerResult result,
                              ExtractedRelation newExtractedRelation) {

        // 1) 没有裁决证据，没法演化（安全保护）
        Citation dominant = pickDominant(result);
        if (dominant == null) {
            log.info("[EVOLVE] no dominant evidence, skip. userId={}", userId);
            return;
        }
        // 2) 本次消息没有抽取出 relation，也没法演化（仅裁决）
        if (newExtractedRelation == null) {
            log.info("[EVOLVE] no extracted relation, only decision. userId={}, dominant={}", userId, dominant);
            return;
        }
        // 3) 判定同向 or 反向
        SemanticRelation rel = judgeRelation(newExtractedRelation, dominant);
        log.info("[EVOLVE] relation={}, dominantPol={}, newPol={}, key={}",
                rel, dominant.polarity(), newExtractedRelation.polarity(), buildLogKey(newExtractedRelation));
        // 4) 执行演化
        switch (rel) {
            case SUPPORT -> handleSupport(userId, dominant, newExtractedRelation);
            case OPPOSE  -> handleOppose(userId, dominant, newExtractedRelation);
            case NEUTRAL -> log.info("[EVOLVE] neutral, do nothing. userId={}", userId);
        }
    }

    public void persistEpistemicEvent(String userId, EpistemicEvent event) {
        String cypher = """
        MATCH (u:User {id: $userId})
        
        CREATE (e:EpistemicEvent {
          id: $eventId,
          type: $type,
          at: datetime($at),
          reason: $reason,
          triggerKey: $triggerKey
        })
        
        CREATE (u)-[:EMITTED]->(e)
        
        WITH e
        UNWIND $deltas AS d
        
        MATCH (c:Claim {key: d.claimKey})
        
        CREATE (e)-[:AFFECTS {
          before: d.before,
          after: d.after,
          delta: d.delta,
          direction: d.direction
        }]->(c)
        """;
        //List<Map<String, Object>>
        List deltas = event.deltas().stream()
                .map(d -> Map.of(
                        "claimKey", d.claimKey(),
                        "before", d.beforeConfidence(),
                        "after", d.afterConfidence(),
                        "delta", d.delta(),
                        "direction", d.direction().name()
                ))
                .toList();

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Map.of(
                        "userId", userId,
                        "eventId", event.eventId(),
                        "type", event.type().name(),
                        "at", event.at().toString(),
                        "reason", event.reason(),
                        "triggerKey", event.triggerKey(),
                        "deltas", deltas
                ));
                return null;
            });
        }
    }

    /**
     *
     * @param userId
     * @param dominant 根据Neo4jGraphAnswerer.queryClaims方法查询且裁决得到的
     * @param r 用户当前聊天msg抽取的ExtractedRelation，是一个新的claim
     */
    private void handleSupport(String userId, Citation dominant, ExtractedRelation r) {
        log.info("[EVOLVE][SUPPORT] userId={},key={}", userId, buildLogKey(r));
        double delta = computeDelta(dominant.confidence(), true);
        /*核心事情：提升 dominant 的置信度*/
        upsertAndSupport(userId,
                ExtractedRelation.relationFromDominant(dominant),
                computeDelta(dominant.confidence(), true) );

        //logSupportTrace(userId, dominant, r, delta, "support");
        ClaimDelta claimDelta = applySupportDelta(userId, dominant, delta);

        maybeTransitionStatus(userId, JSONObject.parseObject(claimDelta.claimKey(),ClaimEvidence.class));

        EpistemicEvent epistemicEvent =new SupportEvent(
                UUID.randomUUID().toString(),
                userId,
                Instant.now(),
                buildCitationKey(r),    // ✅ triggerKey：新抽取的 relation
                "support",
                buildCitationKey(dominant),  // ✅ 被支持的 claim
                List.of(claimDelta));
        persistEpistemicEvent(userId,epistemicEvent);
    }

    /**
     * 它只做三件事（很干净）：
     * 	1.	定位：用 dominant 的五元组 MATCH 到那条 Claim
     * 	2.	更新：confidence += delta（clamp 到 0~1），写回 Neo4j，同时更新 updatedAt
     * 	3.	返回快照：构造一个 updated ClaimEvidence，给 maybeTransitionStatus 用
     * @param userId
     * @param dominant
     * @param delta
     * @return
     */
    private ClaimDelta  applySupportDelta(String userId, Citation dominant, double delta) {
        // 1) 读取“被支持的旧 claim”（dominant 对应的五元组）
        ClaimEvidence before = loadClaimEvidence(
                userId,
                dominant.subjectId(),
                PredicateType.valueOf(dominant.predicate()),
                dominant.objectId(),
                Quantifier.valueOf(dominant.quantifier()),
                dominant.polarity()
        );
        // 2) 计算新值
        double newConfidence = clamp(before.confidence() + delta, 0.0, 1.0);
        // 3) 写回（这里只写 confidence + updatedAt；status 交给 maybeTransitionStatus 去记录迁移）
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
          subjectId:$sid,
          predicate:$pred,
          objectId:$oid,
          quantifier:$q,
          polarity:$pol
        })
        SET c.confidence = $conf,
            c.updatedAt = datetime()
        RETURN c.updatedAt AS updatedAt
        """;
        Instant updatedAt;
        try (Session session = driver.session()) {
            var rec = session.executeWrite(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "sid", dominant.subjectId(),
                            "pred", dominant.predicate(),
                            "oid", dominant.objectId(),
                            "q", dominant.quantifier(),
                            "pol", dominant.polarity(),
                            "conf", newConfidence
                    )).single()
            );
            updatedAt = rec.get("updatedAt").asZonedDateTime().toInstant();
        }
        ClaimDelta claimDelta = new ClaimDelta(
                buildEvidenceKey(before),
                before.confidence(),
                newConfidence,
                delta,
                DeltaDirection.UP
        );
        return claimDelta;
    }




    private ClaimEvidence loadClaimEvidence(
            String userId,
            String subjectId,
            PredicateType predicate,
            String objectId,
            Quantifier quantifier,
            boolean polarity
    ) {
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
          subjectId:$sid,
          predicate:$pred,
          objectId:$oid,
          quantifier:$q,
          polarity:$pol
        })
        RETURN
          c.subjectId AS subjectId,
          c.predicate AS predicate,
          c.objectId AS objectId,
          c.quantifier AS quantifier,
          c.polarity AS polarity,
          c.epistemicStatus AS epistemicStatus,
          coalesce(c.confidence, 0.5) AS confidence,
          c.source AS source,
          c.batch AS batch,
          c.updatedAt AS updatedAt,
          coalesce(c.priority, 0) AS priority
        """;
        try (Session session = driver.session()) {
            var rec = session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "sid", subjectId,
                            "pred", predicate.name(),
                            "oid", objectId,
                            "q", quantifier.name(),
                            "pol", polarity
                    )).single()
            );
            // 注意：Neo4j 返回的类型你可按你项目里已有的工具再封装一下
            String predStr = rec.get("predicate").asString();
            String qStr = rec.get("quantifier").asString();
            String statusStr = rec.get("epistemicStatus").isNull() ? null : rec.get("epistemicStatus").asString();
            EpistemicStatus status = statusStr == null ? null : EpistemicStatus.valueOf(statusStr);

            return new ClaimEvidence(
                    rec.get("subjectId").asString(),
                    PredicateType.valueOf(predStr),
                    rec.get("objectId").asString(),
                    Quantifier.valueOf(qStr),
                    rec.get("polarity").asBoolean(),
                    status,
                    rec.get("confidence").asDouble(),
                    rec.get("source").isNull() ? null : Source.valueOf(rec.get("source").asString()),
                    rec.get("batch").isNull() ? null : rec.get("batch").asString(),
                    rec.get("updatedAt").isNull() ? null : rec.get("updatedAt").asZonedDateTime().toInstant(),
                    rec.get("priority").asInt()
            );
        }
    }

    /**
     *
     * @param v 之前的confidence
     * @param lo
     * @param hi
     * @return
     */
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void maybeTransitionStatus(String userId, ClaimEvidence c) {
        String current = c.epistemicStatus() == null ? "UNKNOWN" : c.epistemicStatus().name();
        String next = deriveStatus(c);
        if (current.equals(next)) {
            return; // 🚫 没变化，不记录
        }
        writeStatusTransition(userId, c, current, next);
    }

    private void writeStatusTransition(
            String userId,
            ClaimEvidence c,
            String from,
            String to
    ) {
        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(cl:Claim {
                  subjectId:$sid,
                  predicate:$pred,
                  objectId:$oid,
                  quantifier:$q,
                  polarity:$pol,
                  legacy:false
                })
                SET cl.epistemicStatus = $to,
                    cl.updatedAt = datetime()
            
                MERGE (fromS:EpistemicStatus {name:$from})
                MERGE (toS:EpistemicStatus {name:$to})
            
                CREATE (t:StatusTransition {
                  id: randomUUID(),
                  at: datetime(),
                  reason: 'threshold-derived',
                  from: $from,
                  to: $to,
                  confidence: cl.confidence,
                  supportCount: cl.supportCount
                })
            
                MERGE (cl)-[:HAS_TRANSITION]->(t)
                MERGE (t)-[:FROM]->(fromS)
                MERGE (t)-[:TO]->(toS)
            
                WITH cl, toS
                OPTIONAL MATCH (cl)-[old:CURRENT_STATUS]->(:EpistemicStatus)
                DELETE old
                MERGE (cl)-[:CURRENT_STATUS]->(toS)
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "sid", c.subjectId(),
                    "pred", c.predicate().name(),
                    "oid", c.objectId(),
                    "q", c.quantifier().name(),
                    "pol", c.polarity(),
                    "from", from,
                    "to", to
            )).consume());
        }
    }


    private String deriveStatus(ClaimEvidence c) {
        double conf = c.confidence();
        if (conf <= 0.15) {
            return "DENIED";
        }
        if (conf >= 0.85) {
            return "CONFIRMED";
        }
        if (conf >= 0.4) {
            return "WEAKLY_SUPPORTED";
        }
        return "UNKNOWN";
    }


    private void handleOppose(String userId,
                              Citation dominant,
                              ExtractedRelation newExtractedRelation) {
        // A. 构造 opposite relation
        ExtractedRelation opposite = buildOppositeRelation(dominant);
        // B. 确保 opposite claim 存在
        ensureClaimSlotExists(userId, opposite);
        // C. 计算本次博弈的 delta
        OpposeDelta delta = computeOpposeDelta(dominant, opposite);
        // D. 执行双向更新（A↓ B↑）
        List<ClaimDelta> claimDeltas = applyOpposeDelta(userId, dominant, opposite, delta);
        // E. 记录演化日志 / 事件
       // logOpposeTrace(userId, dominant, opposite, delta,"oppose");
        ClaimDelta dominanDelta = claimDeltas.get(0);
        ClaimEvidence evidence = JSONObject.parseObject(dominanDelta.claimKey(), ClaimEvidence.class);
        maybeTransitionStatus(userId,evidence);

        EpistemicEvent epistemicEvent = new OpposeEvent(
                UUID.randomUUID().toString(),
                userId,
                Instant.now(),
                buildCitationKey(newExtractedRelation),   // triggerKey
                "oppose",

                buildCitationKey(dominant),
                delta.dominantDelta(),            // ✅ delta 本身

                buildCitationKey(opposite),
                delta.oppositeDelta(),            // ✅ delta 本身

                claimDeltas
        );
        persistEpistemicEvent(userId,epistemicEvent);
    }

/*
    private void logOpposeTrace(
            String userId,
            Citation dominant,
            ExtractedRelation opposite,
            OpposeDelta delta,
            String reason
    ) {
        // 统一 key（这是整个系统的“语义锚点”）
        String dominantKey = buildCitationKey(dominant);
        String oppositeKey = buildKey(opposite);
        // v1 设计：裁决本身就是证据
        String evidenceKey = dominantKey;
        String evidenceSource = dominant.source();
        String cypher = """
        MATCH (u:User {id: $uid})
        CREATE (t:OpposeTrace {
            id: randomUUID(),
            userId: $uid,

            dominantClaimKey: $dk,
            oppositeClaimKey: $ok,

            dominantDelta: $dd,
            oppositeDelta: $od,

            evidenceKey: $ek,
            evidenceSource: $es,

            reason: $reason,
            at: datetime()
        })
        """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(
                        cypher,
                        Map.of(
                                "uid", userId,

                                "dk", dominantKey,
                                "ok", oppositeKey,

                                "dd", delta.dominantDelta(),
                                "od", delta.oppositeDelta(),

                                "ek", evidenceKey,
                                "es", evidenceSource,

                                "reason", reason
                        )
                );
                return null;
            });
        }
        log.debug(
                "[TRACE][OPPOSE] userId={}, dominant={}, opposite={}, dd={}, od={}, reason={}",
                userId,
                dominantKey,
                oppositeKey,
                delta.dominantDelta(),
                delta.oppositeDelta(),
                reason
        );
    }
*/


    /**
     * D. 执行 OPPOSE 的双向 delta 更新
     * - dominant：下降
     * - opposite：上升
     */
    private List<ClaimDelta> applyOpposeDelta(String userId,
                                           Citation dominant,
                                           ExtractedRelation opposite,
                                           OpposeDelta delta) {
        // A) dominant 先读、再写
        ClaimEvidence domBefore = loadClaimEvidence(
                userId,
                dominant.subjectId(),
                PredicateType.valueOf(dominant.predicate()),
                dominant.objectId(),
                Quantifier.valueOf(dominant.quantifier()),
                dominant.polarity()
        );
        double domNew = clamp(domBefore.confidence() + delta.dominantDelta(), 0.0, 1.0);

        // B) opposite 读、再写
        ClaimEvidence oppBefore = loadClaimEvidence(
                userId,
                opposite.subjectId(),
                opposite.predicateType(),
                opposite.objectId(),
                opposite.quantifier(),
                opposite.polarity()
        );
        double oppNew = clamp(oppBefore.confidence() + delta.oppositeDelta(), 0.0, 1.0);

        String cypher = """
        MATCH (u:User {id:$uid})
        MATCH (u)-[:ASSERTS]->(d:Claim {
          subjectId:$dsid, predicate:$dpred, objectId:$doid, quantifier:$dq, polarity:$dpol
        })
        MATCH (u)-[:ASSERTS]->(o:Claim {
          subjectId:$osid, predicate:$opred, objectId:$ooid, quantifier:$oq, polarity:$opol
        })
        SET d.confidence = $dconf, d.updatedAt = datetime(),
            o.confidence = $oconf, o.updatedAt = datetime()
        RETURN d.updatedAt AS updatedAt
        """;
        try (Session session = driver.session()) {
                session.executeWrite(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,

                            "dsid", dominant.subjectId(),
                            "dpred", dominant.predicate(),
                            "doid", dominant.objectId(),
                            "dq", dominant.quantifier(),
                            "dpol", dominant.polarity(),
                            "dconf", domNew,

                            "osid", opposite.subjectId(),
                            "opred", opposite.predicateType().name(),
                            "ooid", opposite.objectId(),
                            "oq", opposite.quantifier().name(),
                            "opol", opposite.polarity(),
                            "oconf", oppNew
                    )).single()
            );
        }
        // 4. 组装 ClaimDelta（这是关键）
        ClaimDelta d1 = new ClaimDelta(
                buildEvidenceKey(domBefore),
                domBefore.confidence(),   // before
                domNew,                   // after
                delta.dominantDelta(),
                DeltaDirection.fromDelta(delta.dominantDelta())
        );

        ClaimDelta d2 = new ClaimDelta(
                buildEvidenceKey(oppBefore),
                oppBefore.confidence(),   // before
                oppNew,                   // after
                delta.oppositeDelta(),
                DeltaDirection.fromDelta(delta.oppositeDelta())
        );
        return List.of(d1, d2);
    }

    /**
     * C. 计算一次 OPPOSE 博弈的 delta（v1 规则）
     *
     * 规则：
     * 1. dominant 置信度越高，下降越多
     * 2. opposite 只获得 dominant 损失的一部分（非对称）
     * 3. 单次博弈存在硬上限，防止翻盘或震荡
     */
    private OpposeDelta computeOpposeDelta(Citation dominant, ExtractedRelation opposite) {
        // ===== v1 固定参数（以后可以策略化） =====
        final double BASE_DEC_RATE = 0.20;   // dominant 基础削弱比例
        final double OPP_GAIN_RATIO = 0.50;  // opposite 只能吃一半
        final double MAX_DOMINANT_DEC = 0.30;
        final double MAX_OPPOSITE_INC = 0.15;

        // ===== 1️⃣ dominant 的下降量 =====
        double dominantConf = dominant.confidence();

        double rawDominantDec = dominantConf * BASE_DEC_RATE;
        double dominantDelta = -Math.min(rawDominantDec, MAX_DOMINANT_DEC);

        // ===== 2️⃣ opposite 的上升量（非对称） =====
        double rawOppositeInc = Math.abs(dominantDelta) * OPP_GAIN_RATIO;
        double oppositeDelta = Math.min(rawOppositeInc, MAX_OPPOSITE_INC);

        // ===== 3️⃣ 日志（非常重要，便于调参） =====
        log.info("[OPPOSE][DELTA] dominantConf={}, dominantΔ={}, oppositeΔ={}, key={}",
                dominantConf,
                dominantDelta,
                oppositeDelta,
                buildLogKey(opposite));

        return new OpposeDelta(dominantDelta, oppositeDelta);
    }



    /**
     * 从 dominant claim 构造 polarity 相反的 ExtractedRelation
     * 用于演化阶段的 opposite 承接
     */
    private ExtractedRelation buildOppositeRelation(Citation dominant){
        return ExtractedRelation.getOppositeExtract(dominant);
    }


    /**
     * 判断裁决的calim与当前新声明的claim是否同向或反向
     * @param r 从当前会话msg抽取得到的ExtractedRelation
     * @param dominant 查询图库中且经过裁决得到的claim
     * @return
     */
    private SemanticRelation judgeRelation(ExtractedRelation r,
                                           Citation dominant) {
        // 1️⃣ 安全保护
        if (r == null || dominant == null) {
            return SemanticRelation.NEUTRAL;
        }

        // 2️⃣ 语义 key 不一致，直接 NEUTRAL
        // （防止“我有特斯拉” vs “我有房子”这种误伤）
        if (!Objects.equals(r.subjectId(), dominant.subjectId())
                || !Objects.equals(r.predicateType().name(), dominant.predicate())
                || !Objects.equals(r.objectId(), dominant.objectId())
                || !Objects.equals(r.quantifier().name(), dominant.quantifier())) {
            return SemanticRelation.NEUTRAL;
        }

        // 3️⃣ polarity 相同 → SUPPORT
        if (r.polarity() == dominant.polarity()) {
            return SemanticRelation.SUPPORT;
        }

        // 4️⃣ polarity 相反 → OPPOSE
        return SemanticRelation.OPPOSE;
    }


    private double computeDelta(double baseConfidence, boolean increase) {
        double step = 0.05;
        if (increase) {
            return Math.min(step, 1.0 - baseConfidence);
        } else {
            return Math.min(step, baseConfidence);
        }
    }

  /*  private double computeDelta(
            AnswerResult decision,
            ExtractedRelation r,
            ClaimEvidence claim,
            SemanticRelation rel) {
        double base;
        //同向则提升原claim的置信度
        if (rel == SemanticRelation.SUPPORT) {
            switch (claim.epistemicStatus()) {
                case HYPOTHETICAL -> base = 0.10;
                case CONFIRMED   -> base = 0.02;
                case REJECTED    -> base = 0.05;
                default          -> base = 0;
            }
            //反向则压低原calim置信度，且抬高新claim的置信度
        } else if (rel == SemanticRelation.OPPOSE) {
            switch (claim.epistemicStatus()) {
                case HYPOTHETICAL -> base = -0.20;
                case CONFIRMED   -> base = -0.15;
                case REJECTED    -> base = -0.02;
                default          -> base = 0;
            }
        } else {
            return 0;
        }
        // 裁决加权
        if (decision.answered() && decision.supports(r)) {
            base *= 1.5;
        }
        return base;
    }
*/
    /**
     * 取出裁决得到的claim
     * @param result
     * @return
     */
    private Citation pickDominant(AnswerResult result) {
        // 你如果没有 topEvidence()，就用 result.citations / evidences 里第一个
        return result == null ? null : result.citations().get(0);
    }



    private List<ClaimEvidence> queryClaimsForEvolution(String userId, ExtractedRelation r) {
        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim)
                WHERE 
                    c.predicate = $pred 
                AND c.legacy = false
                WITH c,
                CASE
                  WHEN toUpper(c.quantifier) = 'ANY' AND c.polarity = false THEN 0
                  WHEN toUpper(c.quantifier) = 'ANY' AND c.polarity = true  THEN 1
                  WHEN toUpper(c.quantifier) = 'ONE' AND c.polarity = true  THEN 2
                  WHEN toUpper(c.quantifier) = 'ONE' AND c.polarity = false THEN 3
                  ELSE 9
                END AS pri
                RETURN
                  c.subjectId  AS subjectId,
                  c.predicate  AS predicate,
                  c.objectId   AS objectId,
                  c.quantifier AS quantifier,
                  c.polarity   AS polarity,
                  c.epistemicStatus AS epistemicStatus,
                  c.confidence AS confidence,
                  c.source     AS source,
                  c.batch      AS batch,
                  c.updatedAt  AS updatedAt,
                  pri          AS priority
                ORDER BY pri ASC, c.confidence DESC, c.updatedAt DESC
                """;
        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "pred", r.predicateType().name()
                    )).list(record -> new ClaimEvidence(
                            record.get("subjectId").asString(),
                            PredicateType.valueOf(record.get("predicate").asString()),
                            record.get("objectId").asString(),
                            Quantifier.valueOf(record.get("quantifier").asString()),
                            record.get("polarity").asBoolean(),
                            EpistemicStatus.valueOf(record.get("epistemicStatus").asString()),
                            record.get("confidence").asDouble(),
                            Neo4jGraphAnswerer.parseSource(record),
                            record.get("batch").isNull() ? null : record.get("batch").asString(),
                            record.get("updatedAt").isNull() ? null
                                    : record.get("updatedAt").asZonedDateTime().toInstant(),
                            record.get("priority").asInt()
                    ))
            );
        }
    }


    /**
     * 确保新的声明存在。若没有，则新增本次claim
     * @param userId
     * @param r
     */
    private void ensureClaimSlotExists(String userId, ExtractedRelation r) {
        // 这个方法只负责：
        // - 如果 polarity 对应的 claim 不存在 → 创建一条
        // - 如果存在 → 什么都不做（交给 upsertAndSupport）
        if (!Neo4jGraphAnswerer.claimExistsRaw(userId, r)) {
            createInitialClaimSlot(userId, r);
        }
    }



    private void createInitialClaimSlot(String userId, ExtractedRelation r) {
        String cypher = """
        MERGE (u:User {id: $uid})
        MERGE (u)-[:ASSERTS]->(c:Claim {
          subjectId:  $sid,
          predicate:  $pred,
          objectId:   $oid,
          quantifier: $q,
          polarity:   $pol,
          legacy:     $legacy
        })
        ON CREATE SET
          c.confidence      = $initConf,
          c.supportCount    = 0,
          c.source          = $source,
          c.batch           = $batch,
          c.generation      = $generation,
          c.epistemicStatus = 'HYPOTHETICAL',
          c.createdAt       = datetime(),
          c.updatedAt       = datetime()
        ON MATCH SET
          c.updatedAt       = datetime()
        """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,

                    "sid", r.subjectId(),
                    "pred", r.predicateType().name(),
                    "oid", r.objectId(),
                    "q", r.quantifier().name(),
                    "pol", r.polarity(),
                    "legacy", r.generation().isLegacy(),

                    // slot 初始值（刻意偏低）
                    "initConf", Math.min(r.confidence(), 0.3),

                    "source", r.source().name(),
                    "batch", "slot-init",
                    "generation", r.generation().name()
            )).consume());
        }
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
                            WHEN  c.confidence IS NULL THEN $baseConf
                            WHEN (c.confidence * 0.995) + $inc > 0.99 THEN 0.99
                            ELSE (c.confidence * 0.995) + $inc
                          END,
                          c.supportCount    = coalesce(c.supportCount, 0) + 1,
                          c.updatedAt       = datetime(),
                          c.lastSupportedAt = datetime()
                        
                        WITH c
                        MERGE (s:EpistemicStatus {name: coalesce(c.epistemicStatus,'UNKNOWN')})
                        
                        WITH c, s
                        OPTIONAL MATCH (c)-[old:CURRENT_STATUS]->(:EpistemicStatus)
                        FOREACH (_ IN CASE WHEN old IS NULL THEN [] ELSE [1] END |
                          DELETE old
                        )
                        
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


    private String buildLogKey(ExtractedRelation r) {
        return JSON.toJSONString(r);
    }


    private String buildCitationKey(Citation c) {
        return String.join("|",
                c.subjectId(),
                c.predicate(),
                c.objectId(),
                c.quantifier(),
                String.valueOf(c.polarity())
        );
    }
    private String buildCitationKey(ExtractedRelation e) {
        return String.join("|",
                e.subjectId(),
                e.predicateType().name(),
                e.objectId(),
                e.quantifier().name(),
                String.valueOf(e.polarity())
        );
    }

    //ClaimEvidence e
    //String s, PredicateType predicate, String s1, Quantifier quantifier, boolean polarity
    private String buildEvidenceKey(ClaimEvidence e) {
        return String.join("|",
                e.subjectId(),
                e.predicate().name(),
                e.objectId(),
                e.quantifier().name(),
                String.valueOf(e.polarity())
        );
    }
}