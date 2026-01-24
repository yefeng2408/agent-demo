package com.yef.agent.memory;

/**
 * 认知状态
 */
public enum EpistemicStatus {
    CONFIRMED,        // 认知上“确认”：用户明确断言/重复确认
    DENIED,           // 用户明确否认
    HYPOTHETICAL,     // 假设/如果/可能/我想象
    UNKNOWN           // 不足以入库
}
