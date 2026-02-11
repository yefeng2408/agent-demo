package com.yef.agent.chat;

import com.yef.agent.advisor.PersonaMemoryAdvisor;
import com.yef.agent.advisor.UserPersonaAdvisor;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.GraphAnswerer;
import com.yef.agent.graph.answer.Neo4jGraphAnswerer;
import com.yef.agent.graph.eum.InteractionType;
import com.yef.agent.graph.extract.*;
import com.yef.agent.graph.llm.LlmPolisher;
import com.yef.agent.graph.writer.Neo4jGraphWriter;
import com.yef.agent.service.ClaimConfidenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatClient personalChatClient;
    private final PersonaMemoryAdvisor personaMemoryAdvisor;
    private final UserPersonaAdvisor userPersonaAdvisor;
    private final Neo4jGraphAnswerer graphAnswerer;
    private final LlmPolisher llmPolisher;
    private final Neo4jGraphWriter neo4jGraphWriter;
    private final LlmInteractionAdapter llmInteractionAdapter;
    private final EpistemicRouter epistemicRouter;


    public ChatController(@Qualifier("personalChatClient") ChatClient personalChatClient,
                          PersonaMemoryAdvisor personaMemoryAdvisor,
                          UserPersonaAdvisor userPersonaAdvisor,
                          Neo4jGraphAnswerer graphAnswerer,
                          LlmPolisher llmPolisher,
                          Neo4jGraphWriter neo4jGraphWriter,
                          LlmInteractionAdapter llmInteractionAdapter,
                          EpistemicRouter epistemicRouter
                          ) {
        this.personalChatClient = personalChatClient;
        this.personaMemoryAdvisor = personaMemoryAdvisor;
        this.userPersonaAdvisor = userPersonaAdvisor;
        this.graphAnswerer=graphAnswerer;
        this.llmPolisher = llmPolisher;
        this.neo4jGraphWriter=neo4jGraphWriter;
        this.llmInteractionAdapter = llmInteractionAdapter;
        this.epistemicRouter = epistemicRouter;
    }


    /**
     * User Question
     *    ↓
     * GraphAnswerer（裁决）
     *    ↓
     * ExtractedRelation（Decision）
     *    ↓
     * Citations（Evidence）
     *    ↓
     * LLM Explainer（只解释）
     *    ↓
     * Neo4jGraphWriter.writeAnswer（写回）
     *    ↓
     * ClaimConfidenceService（状态更新）
     *
     * @param msg
     * @param userId
     * @return
     */
    @GetMapping("/personal")
    public String chat(@RequestParam String msg, @RequestParam(defaultValue = "debug-user") String userId) {
        InteractionClassifier.ClassificationResult cls = llmInteractionAdapter.classify(userId, msg);

        InteractionType type = (cls == null || cls.interactionType() == null)
                ? SimpleInteractionClassifier.classify(msg)
                : cls.interactionType();

        if (type != InteractionType.ASK) {
            return epistemicRouter.handleWrite(userId, msg, type);
        }

        // ASK path
        AnswerResult graphAnswer = graphAnswerer.answer(userId, msg);
        if (graphAnswer.decision()!=null) {
            String explain = llmPolisher.explain(graphAnswer);
            if(graphAnswer.relation()!=null) {
                neo4jGraphWriter.writeAnswer(
                        userId,
                        graphAnswer.relation(),
                        graphAnswer.citations(),
                        explain);
            }
            return explain;
        }

        // fallback to LLM
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
            return answer; // 禁止写 Claim
        }
        // 2.4 响应后处理（⚠️ 这里以后会被 GraphExtraction 替换）
        personaMemoryAdvisor.onResponse(userId, msg, answer);
        return answer;
    }

}

@Slf4j
@Component
 class EpistemicRouter {

    private final GraphRelationExtractor relationExtractor;
    private final GraphAnswerer graphAnswerer;
    private final ClaimConfidenceService claimConfidenceService;

    EpistemicRouter(GraphRelationExtractor relationExtractor,
                    GraphAnswerer graphAnswerer,
                    ClaimConfidenceService claimConfidenceService) {
        this.relationExtractor = relationExtractor;
        this.graphAnswerer = graphAnswerer;
        this.claimConfidenceService = claimConfidenceService;
    }

    public String handleWrite(String userId, String msg, InteractionType type) {
        if (type == InteractionType.ASK) {
            return "ASK 不允许写入";
        }
        // ASSERT / CHALLENGE 才会进来
        List<ExtractedRelation> relations = relationExtractor.extract(userId, msg);
        if (relations == null || relations.isEmpty()) {
            return "未抽取到可写入的关系";
        }

        ExtractedRelation r = relations.stream()
                .max(Comparator.comparingDouble(ExtractedRelation::confidence))
                .orElse(relations.get(0));

        // 注意：这里不能调用 graphAnswerer.answer(msg)
        AnswerResult dominantView = graphAnswerer.answerByRelation(userId, r);

        // 如果没有任何历史 claim，这是“首个断言”
        if (dominantView == null
                || dominantView.citations() == null
                || dominantView.citations().isEmpty()) {

            // 👉 直接创建新 claim（不走 applyAnswer）
            claimConfidenceService.createInitialClaim(userId, r);
            return "已写入（首次声明）: " + r.toReadableText();
        }

        // ✅ applyAnswer 只允许在 ASSERT/CHALLENGE
        claimConfidenceService.applyAnswer(userId, dominantView, r,type);

        return "已写入/更新: " + r.toReadableText();
    }




}
