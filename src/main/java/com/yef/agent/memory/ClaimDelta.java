package com.yef.agent.memory;

/**
 * 变化描述: 这个事件让 claimKey 的 confidence += delta
 * @param claimKey
 * @param delta
 */
public record ClaimDelta(
        String claimKey,
        double delta
) {}