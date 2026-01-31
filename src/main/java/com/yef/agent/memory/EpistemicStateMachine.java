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


}