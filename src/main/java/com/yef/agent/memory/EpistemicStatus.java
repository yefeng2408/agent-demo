package com.yef.agent.memory;

/**
 * 认知状态
 */
@Deprecated
public enum EpistemicStatus {
    CONFIRMED,        // 认知上“确认”：用户明确断言/重复确认
    HYPOTHETICAL,     // 假设/如果/可能/我想象
    DENIED,           // 用户明确否认
    UNKNOWN;           // 不足以入库

    public static EpistemicStatus fromGraph(String raw) {
        if (raw == null) return UNKNOWN;

        return switch (raw) {
          /*  // v2 / legacy 兼容
            case "WEAKLY_SUPPORTED" -> HYPOTHETICAL;
            case "SUPPORTED" -> HYPOTHETICAL;
            case "STRONGLY_SUPPORTED" -> CONFIRMED;*/
            // v3 正规值
            case "CONFIRMED" -> CONFIRMED;
            case "DENIED" -> DENIED;
            case "HYPOTHETICAL" -> HYPOTHETICAL;
            case "UNKNOWN" -> UNKNOWN;

            default -> UNKNOWN; // 防脏数据
        };
    }

}
