package com.yef.agent.memory.selfHealing;

/**
 * 调整置信度（不是暴力跳变）
 *
 * “这个 claim 的置信度，需要被 相对调整”
 *  而不是：
 * 	•	❌ 直接设成 0.9
 * 	•	❌ 强行推过 0.6
 * 	置信度是演化变量，不是裁决阈值
 *
 * @param claimId
 * @param multiplier
 */
public record ConfidenceAdjust(
        String claimId,
        double multiplier
) implements ClaimMutation {

    @Override
    public String claimKey() {
        return "";
    }

}