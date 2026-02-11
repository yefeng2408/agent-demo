package com.yef.agent.memory;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.util.SpringHoldUtils;
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
        DeltaKind kind
) {


    public static ClaimDelta confidenceOnly(Citation dominant, double before, double after) {
        Objects.requireNonNull(dominant, "dominant citation must not be null");
        KeyCodec keyCodec = SpringHoldUtils.getBean("keyCodec");
        String claimKey = keyCodec.buildCitationKey(dominant); // ✅ 确保与你的 evidenceKey 规则一致
        double delta = after - before;

        DeltaDirection dir = (delta >= 0) ? DeltaDirection.UP : DeltaDirection.DOWN;

        return new ClaimDelta(
                claimKey,
                before,
                after,
                delta,
                dir,
                DeltaKind.CONFIDENCE_ONLY
        );
    }

   public enum DeltaKind {
        CONFIDENCE_ONLY,
        STATUS_TRANSITION
    }

}



