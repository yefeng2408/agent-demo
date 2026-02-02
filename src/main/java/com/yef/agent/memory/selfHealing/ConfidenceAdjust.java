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
        return claimId; // 主语 claim（被修改的一方）
    }

    /**
     * 根据当前置信度计算调整后的新置信度。
     *
     * @param oldConfidence 当前 Claim 的置信度（0.0 ~ 1.0）
     * @return 调整后的置信度（已做边界保护）
     */
    public double toConfidence(double oldConfidence) {
        double next = oldConfidence * (1.0 + multiplier);

        // 防止越界
        if (next < 0.0) return 0.0;
        if (next > 1.0) return 1.0;

        return next;
    }

}