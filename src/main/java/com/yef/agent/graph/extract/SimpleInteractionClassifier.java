package com.yef.agent.graph.extract;

import com.yef.agent.graph.eum.InteractionType;


public class SimpleInteractionClassifier {

    public static InteractionType classify(String msg) {
        msg = msg.trim();

        // 1. 明确疑问
        if (msg.endsWith("?") || msg.endsWith("？")
                || msg.startsWith("是否")
                || msg.startsWith("有没有")
                || msg.startsWith("我是否")) {
            return InteractionType.ASK;
        }

        // 2. 否定 / 反驳语气
        if (msg.contains("不是")
                || msg.contains("没有")
                || msg.contains("不对")
                || msg.contains("我并没有")) {
            return InteractionType.CHALLENGE;
        }

        // 3. 默认当作声明
        return InteractionType.ASSERT;
    }
}
