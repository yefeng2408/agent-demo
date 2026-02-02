package com.yef.agent.memory;

/**
 * 变化过程: 这个事件让 claimKey 的 confidence += delta，唯一允许直接修改 confidence 的表达
 * @param claimKey
 * @param delta
 */
public record ClaimDelta(
        String claimKey,        // 被影响的 Claim
        double beforeConfidence,
        double afterConfidence,
        double delta,
        DeltaDirection direction // UP / DOWN
) {

}