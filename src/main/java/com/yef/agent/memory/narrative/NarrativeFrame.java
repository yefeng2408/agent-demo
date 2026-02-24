package com.yef.agent.memory.narrative;

public enum NarrativeFrame {
    STABLE_BELIEF,          // 一直没变
    REINFORCED_BELIEF,      // 被加固
    CHANGED_MIND,           // 被推翻
    UNCERTAIN_SHIFT         // 不确定 → 不确定
}