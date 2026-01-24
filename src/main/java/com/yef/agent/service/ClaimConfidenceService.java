package com.yef.agent.service;

import com.alibaba.fastjson.JSON;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.answer.Neo4jGraphAnswerer;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.SemanticRelation;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.memory.EpistemicStateMachine;
import com.yef.agent.memory.EpistemicStatus;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        maybeTransition(userId, r, "answer_loop");
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
                rel, dominant.polarity(), newExtractedRelation.polarity(), buildKey(newExtractedRelation));
        // 4) 执行演化
        switch (rel) {
            case SUPPORT -> handleSupport(userId, dominant, newExtractedRelation);
            case OPPOSE  -> handleOppose(userId, dominant, newExtractedRelation);
            case NEUTRAL -> log.info("[EVOLVE] neutral, do nothing. userId={}", userId);
        }
    }

    /**
     *
     * @param userId
     * @param dominant 根据Neo4jGraphAnswerer.queryClaims方法查询且裁决得到的
     * @param r 用户当前聊天msg抽取的ExtractedRelation，是一个新的claim
     */
    private void handleSupport(String userId, Citation dominant, ExtractedRelation r) {
        log.info("[EVOLVE][SUPPORT] userId={},key={}", userId, buildKey(r));
        double delta = computeDelta(dominant.confidence(), true);
        /*核心事情：提升 dominant 的置信度*/
        upsertAndSupport(userId,
                ExtractedRelation.relationFromDominant(dominant),
                computeDelta(dominant.confidence(), true) );

        logSupportTrace(userId, dominant, r, delta, "user statement contradicts previous claim");
    }

    private void logSupportTrace(
            String userId,
            Citation dominant,
            ExtractedRelation supported,
            double delta,
            String reason) {
        // 统一 claim key（系统级唯一身份）
        String supportedKey = buildKey(supported);
        // v1 规则：裁决本身就是证据
        String evidenceKey = buildCitationKey(dominant);
        String evidenceSource = dominant.source();
        String cypher = """
        MATCH (u:User {id: $uid})
        CREATE (t:SupportTrace {
            id: randomUUID(),
            userId: $uid,

            supportedClaimKey: $sk,
            delta: $delta,

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

                                "sk", supportedKey,
                                "delta", delta,

                                "ek", evidenceKey,
                                "es", evidenceSource,

                                "reason", reason
                        )
                );
                return null;
            });
        }

        log.debug(
                "[TRACE][SUPPORT] userId={}, supported={}, delta={}, evidence={}, reason={}",
                userId,
                supportedKey,
                delta,
                evidenceKey,
                reason
        );
    }


    private void handleOppose(String userId, Citation dominant, ExtractedRelation newExtractedRelation) {
        // A. 构造 opposite relation
        ExtractedRelation opposite = buildOppositeRelation(dominant);

        // B. 确保 opposite claim 存在
        ensureClaimSlotExists(userId, opposite);

        // C. 计算本次博弈的 delta
        OpposeDelta delta = computeOpposeDelta(dominant, opposite);

        // D. 执行双向更新（A↓ B↑）
        applyOpposeDelta(userId, dominant, opposite, delta);

        // E. 记录演化日志 / 事件
        logOpposeTrace(userId, dominant, opposite, delta,"user statement contradicts previous claim");
    }

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


    /**
     * D. 执行 OPPOSE 的双向 delta 更新
     * - dominant：下降
     * - opposite：上升
     */
    private void applyOpposeDelta(String userId, Citation dominant,
            ExtractedRelation opposite,
            OpposeDelta delta) {
        // ===== 1️⃣ 压制 dominant（负向 delta）=====
        if (delta.dominantDelta() < 0) {
            downscaleClaim(
                    userId,
                    ExtractedRelation.relationFromDominant(dominant),
                    Math.abs(delta.dominantDelta())
            );
        }

        // ===== 2️⃣ 扶持 opposite（正向 delta）=====
        if (delta.oppositeDelta() > 0) {
            upsertAndSupport(
                    userId,
                    opposite,
                    delta.oppositeDelta()
            );
        }
        log.info("[OPPOSE][APPLY] userId={}, dominantΔ={}, oppositeΔ={}",
                userId,
                delta.dominantDelta(),
                delta.oppositeDelta());
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
                buildKey(opposite));

        return new OpposeDelta(dominantDelta, oppositeDelta);
    }

    /**
     * 确保 opposite claim 存在
     * 不负责置信度提升，只负责“有这个仓库”(claim)
     */
    private void ensureOppositeExists(String userId, ExtractedRelation opposite) {
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
          c.confidence      = $initConf,
          c.supportCount    = 0,
          c.source          = $source,
          c.batch           = $batch,
          c.generation      = $generation,
          c.epistemicStatus = 'HYPOTHETICAL',
          c.createdAt       = datetime(),
          c.updatedAt       = datetime()
        ON MATCH SET
          c.updatedAt = datetime()
    """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(
                    cypher,
                    parameters(
                            "uid", userId,
                            "sid", opposite.subjectId(),
                            "pred", opposite.predicateType().name(),
                            "oid", opposite.objectId(),
                            "q", opposite.quantifier().name(),
                            "pol", opposite.polarity(),
                            "legacy", opposite.generation().isLegacy(),
                            "initConf", Math.min(opposite.confidence(), 0.3),
                            "source", opposite.source().name(),
                            "batch", "oppose-init",
                            "generation", opposite.generation().name()
                    )
            ).consume());
        }
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

    //当用户支持一个 Claim 时，对极性相反的 Claim进行信任衰减
    private void downscaleClaim(String userId, ExtractedRelation r, double dec) {
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim)
        WHERE
              c.subjectId=$sid
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

    private String buildKey(ExtractedRelation r) {
        return JSON.toJSONString(r);
    }

    private String buildKey2(Citation citation) {
        return JSON.toJSONString(citation);
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


}