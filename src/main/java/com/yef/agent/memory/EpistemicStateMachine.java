package com.yef.agent.memory;

import org.springframework.stereotype.Component;

@Component
public class EpistemicStateMachine {

    public boolean canTransition(StatusTransitionContext ctx) {

        if (ctx.from() == ctx.to()) return true;

        return switch (ctx.from()) {

            case CONFIRMED ->
                    ctx.to() == EpistemicStatus.DENIED
                            && ctx.evidenceConfidence() > 0.7
                            && ctx.recentEvidenceCount() >= 2;

            case DENIED ->
                    ctx.to() == EpistemicStatus.CONFIRMED
                            && ctx.evidenceConfidence() > 0.8
                            && ctx.recentEvidenceCount() >= 2;

            case HYPOTHETICAL ->
                    ctx.to() != EpistemicStatus.HYPOTHETICAL;

            case UNKNOWN ->
                    true;
        };
    }


    public EpistemicStatus nextGraph(EpistemicStatus cur,
                                     boolean polarity,
                                     double confidence,
                                     long supportCount) {
        // promote thresholds
        final double ENTER_HYPO = 0.55;
        final double PROMOTE_CERTAIN = 0.80;
        final long   PROMOTE_SUPPORT = 2;

        // demote thresholds (hysteresis)
        final double DEMOTE_TO_HYPO = 0.65;
        final double DEMOTE_TO_UNKNOWN = 0.40;

        // normalize
        if (cur == null) cur = EpistemicStatus.UNKNOWN;

        switch (cur) {
            case UNKNOWN -> {
                if (confidence >= ENTER_HYPO) return EpistemicStatus.HYPOTHETICAL;
                return EpistemicStatus.UNKNOWN;
            }
            case HYPOTHETICAL -> {
                if (confidence < DEMOTE_TO_UNKNOWN) return EpistemicStatus.UNKNOWN;
                if (confidence >= PROMOTE_CERTAIN && supportCount >= PROMOTE_SUPPORT) {
                    return polarity ? EpistemicStatus.CONFIRMED : EpistemicStatus.DENIED;
                }
                return EpistemicStatus.HYPOTHETICAL;
            }
            case CONFIRMED, DENIED -> {
                if (confidence < DEMOTE_TO_UNKNOWN) return EpistemicStatus.UNKNOWN;
                if (confidence < DEMOTE_TO_HYPO) return EpistemicStatus.HYPOTHETICAL;
                return cur;
            }
        }
        return cur;
    }


}