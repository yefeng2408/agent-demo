package com.yef.agent.graph.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yef.agent.graph.eum.InteractionType;
import com.yef.agent.graph.llm.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LlmInteractionAdapter implements InteractionClassifier{

    private final LlmClient llmClient;
    private final ObjectMapper om = new ObjectMapper();

    public LlmInteractionAdapter(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public ClassificationResult classify(String userId, String rawText) {
        String system = """
                You are an epistemic intent classifier.

                Classify the user utterance strictly as one of:
                - ASK: user asks about current state of knowledge (read-only)
                - ASSERT: user states a belief or fact (may write)
                - CHALLENGE: user denies/corrects/opposes an existing belief (must enter conflict)

                Return ONLY valid JSON with fields:
                { "interactionType": "ASK|ASSERT|CHALLENGE", "confidence": 0.0-1.0, "rationale": "..." }

                Do not output any extra text.
                """;

        String user = """
                userId=%s
                utterance=%s
                """.formatted(userId, rawText);

        try {
            String json = llmClient.chat(system, user);
            InteractionClassifyJson parsed = om.readValue(json, InteractionClassifyJson.class);

            InteractionType type = parsed.interactionType() != null ? parsed.interactionType() : InteractionType.ASK;
            double conf = clamp01(parsed.confidence());

            return new ClassificationResult(type, conf, safe(parsed.rationale()));

        } catch (Exception e) {
            // 兜底：分类失败 → 只读 ASK（防污染、防误写）
            log.warn("LLM classify failed, fallback to ASK. userId={}, raw={}", userId, rawText, e);
            return new ClassificationResult(InteractionType.ASK, 0.0, "fallback: parse_failed");
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }



}
