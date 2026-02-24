package com.yef.agent.memory.intent;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

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
                        你是一个认知意图分类器。

                        根据用户语气，将句子分类为：

                        SELF_CORRECTION  用户推翻旧观点
                        ASSERT_STRONG    强断言
                        WEAK_ASSERT      普通支持
                        HEDGE            保留态度
                        DOUBT            表示怀疑
                        NORMAL           中性陈述

                        只返回结构化结果。
                        """)
                .user(msg)
                .call()
                .entity(IntentOutput.class);

        try {
            EpistemicIntent intent = EpistemicIntent.valueOf(output.intent());
            return new EpistemicIntentResult(intent, output.confidence());
        } catch (Exception e) {
            return new EpistemicIntentResult(
                    EpistemicIntent.NORMAL,
                    0.0
            );
        }
    }
}