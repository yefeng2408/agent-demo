package com.yef.agent.graph.extract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.GraphExtractionPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@Deprecated
public class GraphRelationExtractorV3 implements GraphRelationExtractor {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public GraphRelationExtractorV3(ChatClient chatClient,
                                    ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ExtractedRelation> extract(String userId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Collections.emptyList();
        }

        String systemPrompt = GraphExtractionPrompt.SYSTEM_PROMPT;
        String userPrompt = GraphExtractionPrompt.userPrompt(userId, userMessage);

        try {
            String json = chatClient
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

            // 关键：严格 JSON → DTO
            List<ExtractedRelation> relations = objectMapper.readValue(json, new TypeReference<>() {});

            // 额外兜底校验（防止 LLM 偷懒）
            relations.forEach(this::validate);

            return relations;

        } catch (Exception e) {
            // ❗任何异常：直接丢弃，不写图
            log.warn("[GraphExtractorV3] discard output, reason={}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void validate(ExtractedRelation r) {
        if (r.subjectId() == null || r.subjectId().isBlank()) {
            throw new IllegalArgumentException("subjectId missing");
        }
        if (r.objectId() == null || r.objectId().isBlank()) {
            throw new IllegalArgumentException("objectId missing");
        }
        if (r.confidence() < 0.0 || r.confidence() > 1.0) {
            throw new IllegalArgumentException("confidence out of range");
        }
    }
}