package com.yef.agent.memory.explain;

public enum ExplanationType {

    /** 为什么做出这个裁决（三态中的“确认 / 否认”） */
    DECISION_REASON,

    /** 冲突说明（多条 claim 不一致） */
    CONFLICT_NOTICE,

    /** 置信度 / 强度相关提示（数值、衰减、提升） */
    CONFIDENCE_NOTICE,

    /** 引导用户澄清 / 追问 */
    FOLLOW_UP_SUGGESTION,

    /** 不确定态的根本原因（不是数值，是认知判断） */
    //UNCERTAIN_REASON,

    /** 引导用户澄清事实（触发下一次 claim 写入） */
    //CLARIFY_QUESTION,

    /** 认知状态迁移说明（为什么从 A → B） */
    STATUS_TRANSITION,

    /** 覆盖原因 */
    OVERRIDDEN_REASON

}