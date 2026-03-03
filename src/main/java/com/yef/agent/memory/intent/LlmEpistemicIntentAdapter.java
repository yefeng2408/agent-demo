package com.yef.agent.memory.intent;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 抽取
 */
@Component
@RequiredArgsConstructor
public class LlmEpistemicIntentAdapter {

    private final ChatClient personalChatClient;

    public EpistemicIntentResult classify(String msg) {

        // ========= 1️⃣ 本地规则优先（超稳） =========
        if (msg.contains("不是")
                || msg.contains("我改一下")
                || msg.contains("我说错")) {

            return new EpistemicIntentResult(
                    EpistemicIntent.SELF_CORRECTION,
                    0.95

            );
        }

        if (msg.contains("我确定")
                || msg.contains("就是")
                || msg.contains("肯定")
                || msg.contains("确实")
                || msg.contains("真的")) {

            return new EpistemicIntentResult(
                    EpistemicIntent.ASSERT_STRONG,
                    0.85
            );
        }

        // ========= 2️⃣ LLM Structured Output =========
        IntentOutput output = personalChatClient
                .prompt()
                .system("""
                        你是一个认知语气分类器。
                        
                                 任务：
                                 根据句子的语气强度与表达方式，
                                 将其分类为以下 6 类之一。
                        
                                 判断规则如下（必须严格遵守）：
                        
                                 1️⃣ SELF_CORRECTION
                                 - 出现对之前观点的推翻或修正。
                                 - 包含：不对、其实、我之前说错了、我改一下、重新说
                        
                                 2️⃣ ASSERT_STRONG
                                 - 出现强烈肯定词。
                                 - 包含：绝对、一定、肯定、毫无疑问、必须、就是
                        
                                 3️⃣ WEAK_ASSERT
                                 - 表达支持，但语气不强。
                                 - 例如：应该是、感觉是、我认为
                        
                                 4️⃣ HEDGE
                                 - 表达保留态度。
                                 - 例如：可能、也许、大概、差不多、好像
                        
                                 5️⃣ DOUBT
                                 - 表达怀疑或否定当前观点。
                                 - 例如：真的吗、不确定、怀疑、是不是
                        
                                 6️⃣ NORMAL
                                 - 没有明显语气标记的普通陈述。
                        
                                 分类原则：
                                 - 优先匹配 SELF_CORRECTION。
                                 - 若出现强烈副词，优先 ASSERT_STRONG。
                                 - 若没有明显情绪词，默认 NORMAL。
                                 - 不要随意全部返回 NORMAL。
                        
                                 仅返回 JSON：
                                 {
                                   "intent": "...",
                                   "confidence": 0.0-1.0
                                 }
                        """)
                .user(msg)
                .call()
                .entity(IntentOutput.class);

        try {
            EpistemicIntent intent = EpistemicIntent.valueOf(output.intent());
            // v7: 分离“分类置信度”和“语气强度”
           // double classificationConfidence = output.confidence() != 0.0 ? output.confidence() : 0.6;

            // 语气强度不再由 LLM 决定，而是本地映射（稳定）
            double assertionStrength = switch (intent) {
                case SELF_CORRECTION -> 0.95;
                case ASSERT_STRONG -> 0.85;
                case WEAK_ASSERT -> 0.65;
                case HEDGE -> 0.45;
                case DOUBT -> 0.35;
                case NORMAL -> 0.5;
            };

            return new EpistemicIntentResult(
                    intent,
                    assertionStrength
            );
        } catch (Exception e) {
            return new EpistemicIntentResult(
                    EpistemicIntent.NORMAL,  0.0d);
        }
    }
}