package com.yef.agent.service;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.answer.Neo4jGraphAnswerer;
import com.yef.agent.graph.eum.InteractionType;
import com.yef.agent.graph.eum.SemanticRelation;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.intent.EpistemicIntentResult;
import com.yef.agent.memory.intent.LlmEpistemicIntentAdapter;
import com.yef.agent.memory.pipeline.EpistemicContext;
import com.yef.agent.memory.pipeline.EpistemicDeltaPipeline;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.repository.ClaimEvidenceRepository;
import com.yef.agent.repository.StatusTransitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    private final EpistemicDeltaPipeline epistemicDeltaPipeline;
    private final ClaimEvidenceRepository claimEvidenceRepository;
    private final KeyCodec keyCodec;
    private final StatusTransitionRepository  statusTransitionRepository;
    private final LlmEpistemicIntentAdapter llmEpistemicIntentAdapter;
    private final DominantService dominantService;


    public ClaimConfidenceService(Driver driver,
                                  EpistemicDeltaPipeline epistemicDeltaPipeline,
                                  ClaimEvidenceRepository claimEvidenceRepository,
                                  KeyCodec keyCodec, StatusTransitionRepository statusTransitionRepository,
                                  LlmEpistemicIntentAdapter llmEpistemicIntentAdapter, DominantService dominantService
    ) {
        this.driver = driver;
        this.epistemicDeltaPipeline = epistemicDeltaPipeline;
        this.claimEvidenceRepository = claimEvidenceRepository;
        this.keyCodec = keyCodec;
        this.statusTransitionRepository = statusTransitionRepository;
        this.llmEpistemicIntentAdapter = llmEpistemicIntentAdapter;
        this.dominantService = dominantService;
    }


    public void applyAnswer(String userId,
                            String msg,
                            AnswerResult result,
                            ExtractedRelation newExtractedRelation,
                            InteractionType type) {

        if (result == null) return;

        String slotKey = keyCodec.buildSlotKey2(newExtractedRelation);
        // ⭐⭐⭐ 直接读取真正 dominant
        Optional<DominantClaimVO> domOpt = dominantService.loadDominantView(userId, slotKey);

        if (domOpt.isEmpty()) {
            log.warn("applyAnswer: dominant missing, slotKey={}", slotKey);
            return;
        }

        ClaimEvidence claimEvidence = domOpt.get().claim();
        Citation dominantClaim = Citation.from(claimEvidence);
        SemanticRelation rel = judgeRelation(newExtractedRelation, dominantClaim);

        if (rel == SemanticRelation.NEUTRAL) {
            return;
        }

        EpistemicIntentResult intentResult = llmEpistemicIntentAdapter.classify(msg);
        if (rel == SemanticRelation.OPPOSE) {
            String challengerClaimKey = keyCodec.buildExtractRelKey(newExtractedRelation);
            ClaimEvidence opposeClaim = claimEvidenceRepository.loadEvidenceClaimByClaimKey(userId, challengerClaimKey);
            if(opposeClaim==null){
                //claimEvidenceRepository.writeDominant();
                claimEvidenceRepository.initOpposeClaim(userId,newExtractedRelation);
            }
        }

        EpistemicContext ctx = EpistemicContext.fromAnswer(
                        userId,
                        result,
                        newExtractedRelation,
                        rel,
                        type,
                        intentResult
                );

        epistemicDeltaPipeline.execute(ctx);
    }

    /**
     * 判断裁决的calim与当前新声明的claim是否同向或反向
     *
     * @param r        从当前会话msg抽取得到的ExtractedRelation
     * @param dominant 查询图库中且经过裁决得到的claim
     * @return
     */
    private SemanticRelation judgeRelation(ExtractedRelation r, Citation dominant) {
        //1.inexistent current new claim or the graph of history claim
        if (r == null || dominant == null) {
            return SemanticRelation.NEUTRAL;
        }

        // 2. 语义 key 不一致（五元组），直接 NEUTRAL,
        // （防止“我有特斯拉” vs “我有房子”这种误伤）
        if (!Objects.equals(r.subjectId(), dominant.subjectId())
                || !Objects.equals(r.predicateType().name(), dominant.predicate())
                || !Objects.equals(r.objectId(), dominant.objectId())
                || !Objects.equals(r.quantifier().name(), dominant.quantifier())) {
            return SemanticRelation.NEUTRAL;
        }

        // 3. polarity 相同 → SUPPORT
        if (r.polarity() == dominant.polarity()) {
            return SemanticRelation.SUPPORT;
        }

        // 4. polarity 相反 → OPPOSE
        return SemanticRelation.OPPOSE;
    }


    /**
     * 取出裁决得到的claim
     *
     * @param result
     * @return
     */
    private Citation pickDominant(AnswerResult result) {
        // 你如果没有 topEvidence()，就用 result.citations / evidences 里第一个
        if (result == null || result.citations() == null || result.citations().isEmpty()) {
            return null;
        }
        return result.citations().get(0);
    }


    /**
     * 确保新的声明存在。若没有，则新增本次claim
     *
     * @param userId
     * @param r
     */
    public void ensureClaimSlotExists(String userId, ExtractedRelation r) {
        // 这个方法只负责：
        // - 如果 polarity 对应的 claim 不存在 → 创建一条
        // - 如果存在 → 什么都不做（交给 upsertAndSupport）
        if (!Neo4jGraphAnswerer.claimExistsRaw(userId, r)) {
            createInitialClaimSlot(userId, r);
        }
    }


    private void createInitialClaimSlot(String userId, ExtractedRelation r) {
        String cypher = """
                    // 1️⃣ User
                    MERGE (u:User {id: $uid})
                
                    // 2️⃣ Claim（五元组）
                    MERGE (u)-[:ASSERTS]->(c:Claim {
                      subjectId:  $sid,
                      predicate:  $pred,
                      objectId:   $oid,
                      quantifier: $q,
                      polarity:   $pol,
                      legacy:     $legacy
                    })
                    ON CREATE SET
                      c.claimKey        = $claimKey,
                      c.confidence      = $initConf,
                      c.supportCount    = 0,
                      c.source          = $source,
                      c.batch           = $batch,
                      c.generation      = $generation,
                      c.epistemicStatus = 'HYPOTHETICAL',
                      c.priority        = 0,
                      c.createdAt       = datetime(),
                      c.updatedAt       = datetime()
                    ON MATCH SET
                      c.updatedAt       = datetime()
                
                    // 3️⃣ ClaimSlot（四元组，不含 polarity）
                    MERGE (slot:ClaimSlot {key:$slotKey})
                
                    MERGE (u)-[:HAS_SLOT]->(slot)
                
                    WITH slot, c
                    MERGE (slot)-[:CONTAINS]->(c)
                """;

        String slotKey = keyCodec.buildSlotKey(
                r.subjectId(),
                r.predicateType(),
                r.objectId(),
                r.quantifier()
        );

        String claimKey = keyCodec.buildExtractRelKey(r);

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,

                    "slotKey", slotKey,
                    "claimKey", claimKey,

                    "sid", r.subjectId(),
                    "pred", r.predicateType().name(),
                    "oid", r.objectId(),
                    "q", r.quantifier().name(),
                    "pol", r.polarity(),
                    "legacy", r.generation().isLegacy(),

                    "initConf", Math.min(r.confidence(), 0.6),

                    "source", r.source().name(),
                    "batch", "slot-init",
                    "generation", r.generation().name()
            )).consume());
        }
    }


    public void createInitialClaim(String userId, ExtractedRelation r) {
        // 只做一件事：创建 claim slot
        ensureClaimSlotExists(userId, r);
        // 设置初始 epistemicStatus
        // EpistemicStatus 为 CONFIRMED 或 ASSERTED，看你定义
        setEpistemicStatus(userId, r, EpistemicStatus.HYPOTHETICAL);
/*        // 首次就写入 dominant 链路
        String slotKey  = keyCodec.buildSlotKey2(r);
        String claimKey = keyCodec.buildExtractRelKey(r);
        if (!claimEvidenceRepository.hasDominant(userId, slotKey)) {
            claimEvidenceRepository.writeDominant(
                    userId,
                    slotKey,
                    claimKey,
                    Math.min(r.confidence(), 0.6),
                    EpistemicStatus.HYPOTHETICAL,
                    "BOOTSTRAP"
            );
        }*/
    }


    private void setEpistemicStatus(String userId,
                                    ExtractedRelation r,
                                    EpistemicStatus status) {

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {

                tx.run("""
                                MATCH (c:Claim {
                                    subjectId: $subjectId,
                                    predicate: $predicate,
                                    objectId: $objectId,
                                    quantifier: $quantifier,
                                    polarity: $polarity,
                                    generation: $generation
                                })
                                SET c.epistemicStatus = $status,
                                    c.updatedAt = datetime()
                                """,
                        Map.of(
                                "subjectId", userId,
                                "predicate", r.predicateType().name(),
                                "objectId", r.objectId(),
                                "quantifier", r.quantifier().name(),
                                "polarity", r.polarity(),
                                "generation", r.generation().name(),
                                "status", status.name()
                        )
                ).consume();
                return null;
            });
        }
    }

}