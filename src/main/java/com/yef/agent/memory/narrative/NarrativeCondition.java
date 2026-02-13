package com.yef.agent.memory.narrative;

public enum NarrativeCondition {
    /**
     * 高稳定性：有效置信度高 + 状态可靠 + 未被近期挑战
     */
    CERTAIN,

    /**
     * 低稳定性：有效置信度中等/偏低 或 状态不够可靠
     */
    TENTATIVE,

    /**
     * 存在争议：override 历史明显 或 近期被挑战
     */
    CONTESTED,

    /**
     * 刚发生过状态变化（刚确认/刚推翻/刚迁移），即使高置信也要收敛语气
     */
    RECENTLY_CHANGED
}