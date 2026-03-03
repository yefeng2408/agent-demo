package com.yef.agent.chat;

import com.alibaba.fastjson.JSON;
import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.GraphAnswerer;
import com.yef.agent.graph.answer.Neo4jGraphAnswerer;
import com.yef.agent.graph.eum.InteractionType;
import com.yef.agent.graph.extract.*;
import com.yef.agent.graph.llm.LlmPolisher;
import com.yef.agent.graph.writer.Neo4jGraphWriter;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.SlotBeliefState;
import com.yef.agent.memory.vo.AgentResponse;
import com.yef.agent.memory.vo.CanonicalRelation;
import com.yef.agent.repository.ClaimEvidenceRepository;
import com.yef.agent.repository.StatusTransitionRepository;
import com.yef.agent.service.ClaimConfidenceService;
import com.yef.agent.service.MemoryResetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final Neo4jGraphAnswerer graphAnswerer;
    private final LlmPolisher llmPolisher;
    private final Neo4jGraphWriter neo4jGraphWriter;
    private final LlmInteractionAdapter llmInteractionAdapter;
    private final EpistemicRouter epistemicRouter;


    public ChatController(Neo4jGraphAnswerer graphAnswerer,
                          LlmPolisher llmPolisher,
                          Neo4jGraphWriter neo4jGraphWriter,
                          LlmInteractionAdapter llmInteractionAdapter,
                          EpistemicRouter epistemicRouter
    ) {
        this.graphAnswerer = graphAnswerer;
        this.llmPolisher = llmPolisher;
        this.neo4jGraphWriter = neo4jGraphWriter;
        this.llmInteractionAdapter = llmInteractionAdapter;
        this.epistemicRouter = epistemicRouter;
    }


    /**
     * User Question
     * ↓
     * GraphAnswerer（裁决）
     * ↓
     * ExtractedRelation（Decision）
     * ↓
     * Citations（Evidence）
     * ↓
     * LLM Explainer（只解释）
     * ↓
     * Neo4jGraphWriter.writeAnswer（写回）
     * ↓
     * ClaimConfidenceService（状态更新）
     *
     * @param msg
     * @param userId
     * @return
     */
    @GetMapping("/personal")
    public AgentResponse chat(@RequestParam String msg,
                              @RequestParam(defaultValue = "debug-user") String userId) {
        InteractionClassifier.ClassificationResult cls = llmInteractionAdapter.classify(userId, msg);

        InteractionType type = (cls == null || cls.interactionType() == null)
                ? SimpleInteractionClassifier.classify(msg)
                : cls.interactionType();

        if (type != InteractionType.ASK) {
            // 先执行认知写入，但不直接返回，让对话继续走 explain 流程
            ExtractedRelation r = epistemicRouter.handleWrite(userId, msg, type);

            // 写入型交互也走一次 explain，让用户感觉是在对话
            AnswerResult graphAnswer = graphAnswerer.extractAsk(userId,r);
            if (graphAnswer.decision() != null) {
                String explain = llmPolisher.explain(graphAnswer);
                if (graphAnswer.relation() != null) {
                    neo4jGraphWriter.writeAnswer(
                            userId,
                            graphAnswer.relation(),
                            graphAnswer.citations(),
                            explain);
                }
                log.info("relation = {}", graphAnswer.relation());
                log.info("decision = {}", graphAnswer.decision());

                log.info("1---->AgentResponse result:{}", JSON.toJSONString(new AgentResponse(explain, graphAnswer)));
                return new AgentResponse(explain, graphAnswer);
            }

            // 如果没有可解释结果，兜底返回写入提示
            log.info("2---->AgentResponse result:{}", JSON.toJSONString(new AgentResponse("本次未能抽取到关系", graphAnswer)));
            return new AgentResponse("本次未能抽取到关系", graphAnswer);
        }

        // ASK path
        String explain=null;
        AnswerResult graphAnswer = graphAnswerer.answer(userId,msg);
        if (graphAnswer.decision() != null) {
            explain = llmPolisher.explain(graphAnswer);
            if (graphAnswer.relation() != null) {
                neo4jGraphWriter.writeAnswer(
                        userId,
                        graphAnswer.relation(),
                        graphAnswer.citations(),
                        explain);
            }
            log.info("3---->AgentResponse result:{}", JSON.toJSONString(new AgentResponse(explain, graphAnswer)));
        }
        return new AgentResponse(explain, graphAnswer);

/*        // fallback to LLM
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("readOnly", true);
        //metadata.put("disableClaimWrite", true);
        // 2.1 请求前处理（v2 记忆逻辑）
        personaMemoryAdvisor.onRequest(msg, metadata);
        // 2.2 拉取用户历史记忆（目前仍来自 MySQL / Milvus）
        List<String> memories = userPersonaAdvisor.getUserMemories(userId);
        String systemPrompt = """
                你是一个有长期记忆的 AI 助手。
                以下是你已知的关于用户的信息：
                %s
                """
                .formatted(String.join("\n", memories));

        // 2.3 调用 LLM
        String answer = personalChatClient
                .prompt()
                .system(systemPrompt)
                .user(msg)
                .call()
                .content();
        if (Boolean.TRUE.equals(metadata.get("disableClaimWrite"))) {
            return new AgentResponse(answer, graphAnswer); // 禁止写 Claim
        }
        // 2.4 响应后处理（⚠️ 这里以后会被 GraphExtraction 替换）
        personaMemoryAdvisor.onResponse(userId, msg, answer);
        log.info("4---->AgentResponse result:{}", JSON.toJSONString(new AgentResponse(answer, graphAnswer)));
        return new AgentResponse(answer, graphAnswer);*/
    }
    @Autowired
    MemoryResetService memoryResetService;


    @ResponseBody
    @DeleteMapping("/personal/reset")
    public AgentResponse reset(@RequestParam(defaultValue = "debug-user3") String userId) {
        memoryResetService.toEmptyUserDataByUserId(userId);
        AgentResponse agentResponse = new AgentResponse();
        log.info("======> reset success");
        return agentResponse;
    }

}



@Slf4j
@Component
class EpistemicRouter {

    private final GraphRelationExtractor relationExtractor;
    private final GraphAnswerer graphAnswerer;
    private final ClaimConfidenceService claimConfidenceService;
    private final DefaultRelationCanonicalizer relationCanonicalizer;
    private final KeyCodec keyCodec;
    private final ClaimEvidenceRepository claimEvidenceRepository;
    private final StatusTransitionRepository statusTransitionRepository;

    EpistemicRouter(GraphRelationExtractor relationExtractor,
                    GraphAnswerer graphAnswerer,
                    ClaimConfidenceService claimConfidenceService,
                    DefaultRelationCanonicalizer relationCanonicalizer,
                    KeyCodec keyCodec, ClaimEvidenceRepository claimEvidenceRepository,
                    StatusTransitionRepository statusTransitionRepository) {
        this.relationExtractor = relationExtractor;
        this.graphAnswerer = graphAnswerer;
        this.claimConfidenceService = claimConfidenceService;
        this.relationCanonicalizer = relationCanonicalizer;
        this.keyCodec = keyCodec;
        this.claimEvidenceRepository = claimEvidenceRepository;
        this.statusTransitionRepository = statusTransitionRepository;
    }


    public ExtractedRelation handleWrite(String userId, String msg, InteractionType type) {
        // ========= 0️⃣ ASK 不参与写入 =========
        if (type == InteractionType.ASK) {
            return null;
        }

        // ========= 1️⃣ 抽取关系 =========
        List<ExtractedRelation> relations = relationExtractor.extract(userId, msg);
        if (relations == null || relations.isEmpty()) {
            return null;
        }

        ExtractedRelation r = relations.stream()
                .max(Comparator.comparingDouble(ExtractedRelation::confidence))
                .orElse(relations.get(0));

        // ========= 2️⃣ Canonicalize =========
        Optional<CanonicalRelation> canonicalize = relationCanonicalizer.canonicalize(r, msg);

        if (canonicalize.isEmpty()) {
            return null;
        }

        r = ExtractedRelation.buildCanonicalRelation(canonicalize.get());
        if (r == null) {
            log.warn("Extractor returned null relation");
            return null;
        }

        String slotKey = keyCodec.buildSlotKey2(r);
        String claimKey = keyCodec.buildExtractRelKey(r);

        // ========= 3️⃣ 判断是否首次 =========
        boolean firstClaim = !Neo4jGraphAnswerer.claimExistsRaw(userId, r);

        // ========= 4️⃣ BOOTSTRAP =========
        if (firstClaim) {
            log.info("BOOTSTRAP claim slot + dominant, slotKey={}", slotKey);
            // 创建 Claim + Slot
            claimConfidenceService.createInitialClaim(userId, r);
            // 写 BeliefState（Dominant 入口）
            claimEvidenceRepository.writeDominant(
                    userId,
                    slotKey,
                    claimKey,
                    SlotBeliefState.WEAKLY_CONFIRMED,
                    "BOOTSTRAP"
            );
        }

        // ========= 5️⃣ 查询当前 dominant 视图 =========
        AnswerResult dominantView = graphAnswerer.answerByRelation(userId, r);

        // ========= 6️⃣ Delta Pipeline =========
        claimConfidenceService.applyAnswer(
                userId,
                msg,
                dominantView,
                r,
                type
        );
        // ⭐⭐⭐ 关键变化：返回 Relation，而不是提示字符串
        return r;
    }


}
