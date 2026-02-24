package com.yef.agent.memory;

import com.yef.agent.memory.intent.EpistemicIntent;
import java.util.Objects;

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
        DeltaDirection direction, // UP / DOWN
        DeltaKind kind,
        EpistemicIntent intent,
        double intentConfidence,
        // ✅ v3.5
       String overrideTargetClaimKey
) {

    public static ClaimDelta confidenceOnly(
            String claimKey,
            double before,
            double after,
            EpistemicIntent intent,
            double intentConfidence,
            String overrideTargetClaimKey
    ) {
        Objects.requireNonNull(claimKey, "claimKey must not be null");

        double delta = after - before;

        DeltaDirection dir =
                (delta >= 0)
                        ? DeltaDirection.UP
                        : DeltaDirection.DOWN;

        return new ClaimDelta(
                claimKey,
                before,
                after,
                delta,
                dir,
                DeltaKind.CONFIDENCE_ONLY,
                intent,
                intentConfidence,
                overrideTargetClaimKey
        );
    }
   public enum DeltaKind {
        CONFIDENCE_ONLY,
        STATUS_TRANSITION
    }

}



