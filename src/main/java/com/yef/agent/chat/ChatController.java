package com.yef.agent.chat;

import com.yef.agent.advisor.PersonaMemoryAdvisor;
import com.yef.agent.advisor.UserPersonaAdvisor;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.GraphWriter;
import com.yef.agent.graph.answer.Neo4jGraphAnswerer;
import com.yef.agent.graph.answer.Neo4jGraphWriter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
    private final Neo4jGraphWriter graphWriter;

    public ChatController(@Qualifier("personalChatClient") ChatClient personalChatClient,
                          PersonaMemoryAdvisor personaMemoryAdvisor,
                          UserPersonaAdvisor userPersonaAdvisor,
                          Neo4jGraphAnswerer graphAnswerer,
                          Neo4jGraphWriter graphWriter) {
        this.personalChatClient = personalChatClient;
        this.personaMemoryAdvisor = personaMemoryAdvisor;
        this.userPersonaAdvisor = userPersonaAdvisor;
        this.graphAnswerer=graphAnswerer;
        this.graphWriter=graphWriter;
    }


    /**
     * ChatController
     *   → GraphRelationExtractorV3
     *      → Neo4jGraphWriter
     *   → Neo4jGraphAnswerer
     *      → 组装语义 → LLM
     * @param msg
     * @param userId
     * @return
     */
    @GetMapping("/personal")
    public String chat(@RequestParam String msg, @RequestParam(defaultValue = "debug-user") String userId) {
        // ===============================
        // ✅ Step 1: Graph 优先裁决（v3 核心）
        // ===============================
        AnswerResult graphAnswer = graphAnswerer.answer(userId, msg);
        if (graphAnswer.answered()) {
            // ✅ Step 3.5：回答 → 写图（闭环）
            ExtractedRelation relation = graphAnswer.extractedRelation();
            if (relation != null) {
                graphWriter.writeRelation(relation);
            }
            // 这里可以选择是否再让 LLM polish
            return graphAnswer.answer();
        }
        // ===============================
        // ⬇️ Step 2: LLM fallback（v2 仍在）
        // ===============================
        Map<String, Object> metadata = new HashMap<>();

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

        // 2.4 响应后处理（⚠️ 这里以后会被 GraphExtraction 替换）
        personaMemoryAdvisor.onResponse(userId, msg, answer);
        return answer;
    }
}
