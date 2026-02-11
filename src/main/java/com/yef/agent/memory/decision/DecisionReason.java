package com.yef.agent.memory.decision;

/**
 * DominantDecision 的裁决原因
 * 用于：
 * - 解释生成
 * - 日志 / 观测
 * - 行为统计
 */
public enum DecisionReason {

    // ===== 确定性裁决 =====

    CONFIRMED_SINGLE,
    DENIED_SINGLE,

    // ===== 冲突态 =====

    MULTIPLE_CONFIRMED,
    HYPOTHETICAL_CONFLICT,

    // ===== 非裁决态 =====

    HYPOTHETICAL_SINGLE,
    LOW_CONFIDENCE_FALLBACK,
    NO_CLAIM,
    NO_BEST
}