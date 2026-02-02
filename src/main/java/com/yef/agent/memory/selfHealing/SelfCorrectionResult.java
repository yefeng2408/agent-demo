package com.yef.agent.memory.selfHealing;

import java.util.List;
import java.util.Optional;

/**
 * Self-Healing 的“判决书（但不是终审）”
 * @param triggered 是否值得修复（大多数事件其实是不值得的）
 * @param mutations 如果修复，要做哪些“可回放的变化”
 * @param dominantHint “如果将来要裁决，我更倾向哪一条”
 *
 * 注意：
 * dominantHint ≠ statusOverride
 * 它只是一个“裁决建议”，不是事实
 */
public record SelfCorrectionResult(
        boolean triggered,
        List<ClaimMutation> mutations,
        Optional<DominantClaimHint> dominantHint
) {

    public static SelfCorrectionResult noop() {
        return new SelfCorrectionResult(
                false,
                List.of(),
                Optional.empty());
    }


}