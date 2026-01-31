package com.yef.agent.memory.explain;

import java.util.Objects;

/**
 * 一条解释性说明（可排序、可裁剪、可去重）
 */
public record ExplanationItem(

        ExplanationType type,

        /**
         * 面向用户的解释文本
         */
        String text,

        /**
         * 越小越优先显示（0 最重要）
         */
        int priority,

        /**
         * 受众：用户 or 开发者
         */
        Audience audience,

        /**
         * 去重 key：相同 key 只保留 priority 更高的一条
         */
        String dedupeKey
) {

    public ExplanationItem {
        Objects.requireNonNull(type);
        Objects.requireNonNull(text);
        Objects.requireNonNull(audience);
        Objects.requireNonNull(dedupeKey);
    }

    public static ExplanationItem user(ExplanationType type, String text, int priority, String dedupeKey) {
        return new ExplanationItem(type, text, priority, Audience.USER, dedupeKey);
    }

    public static ExplanationItem dev(ExplanationType type, String text, int priority, String dedupeKey) {
        return new ExplanationItem(type, text, priority, Audience.DEV, dedupeKey);
    }
}