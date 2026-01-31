package com.yef.agent.memory.explain;

public enum ExplanationType {

    /**
     * 核心裁决原因
     */
    DECISION_REASON,

    /**
     * 冲突说明（多条 claim 不一致）
     */
    CONFLICT_NOTICE,

    /**
     * 置信度 / 不确定性提示
     */
    CONFIDENCE_NOTICE,

    /**
     * 引导用户澄清 / 追问
     */
    FOLLOW_UP_SUGGESTION,

    /**
     * 认知状态迁移说明（解释“为何从 A 变成 B”）
     */
    STATUS_TRANSITION
}