package com.yef.agent.memory.event;

import com.yef.agent.memory.ClaimDelta;

import java.time.Instant;
import java.util.List;

public record OpposeEvent(
        String eventId,
        String userId,
        Instant at,
        String evidenceKey,
        String reason,

        String dominantClaimKey,
        double dominantDelta,

        String oppositeClaimKey,
        double oppositeDelta
) implements EpistemicEvent {

    @Override
    public EpistemicEventType type() {
        return EpistemicEventType.OPPOSE;
    }

    @Override
    public List<ClaimDelta> deltas() {
        return List.of(
                new ClaimDelta(dominantClaimKey, dominantDelta),
                new ClaimDelta(oppositeClaimKey, oppositeDelta)
        );
    }
}