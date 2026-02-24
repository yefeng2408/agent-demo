package com.yef.agent.memory.decision;

/**
 * DominantDecision 的裁决原因
 * 用于：
 * - 解释生成
 * - 日志 / 观测
 * - 行为统计
 */
public enum DecisionReason {

  /*  // ===== 确定性裁决 =====

    CONFIRMED_SINGLE,
    DENIED_SINGLE,

    // ===== 冲突态 =====

    MULTIPLE_CONFIRMED,
    HYPOTHETICAL_CONFLICT,

    // ===== 非裁决态 =====

    HYPOTHETICAL_SINGLE,
    LOW_CONFIDENCE_FALLBACK,
    NO_CLAIM,
    NO_BEST*/

    MOMENTUM_DOMINANT,
    NO_BELIEFSTATE,
    NO_EVIDENCE,
    FALLBACK_CONFIDENCE;


    public static DecisionReason graphDominant(String decisionSource) {
        if (decisionSource == null || decisionSource.isBlank()) {
            return FALLBACK_CONFIDENCE;
        }

        String src = decisionSource.trim().toUpperCase();

        // Graph 已裁决（来自 BeliefState / DOMINANT）
        if ("GRAPH_DOMINANT".equals(src) || "BELIEFSTATE".equals(src)) {
            return MOMENTUM_DOMINANT;
        }

        // 没有 belief
        if ("NO_BELIEFSTATE".equals(src)) {
            return NO_BELIEFSTATE;
        }

        // 没有证据
        if ("NO_EVIDENCE".equals(src)) {
            return NO_EVIDENCE;
        }

        // 默认兜底
        return FALLBACK_CONFIDENCE;
    }
}