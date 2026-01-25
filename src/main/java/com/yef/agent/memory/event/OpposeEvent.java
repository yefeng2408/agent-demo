package com.yef.agent.memory.event;

import com.yef.agent.memory.ClaimDelta;
import java.time.Instant;
import java.util.List;

public record OpposeEvent(
        String eventId,
        String userId,
        Instant at,
        String triggerKey,
        String reason,

        String dominantClaimKey,
        double dominantDelta,

        String oppositeClaimKey,
        double oppositeDelta,

        List<ClaimDelta> deltas

) implements EpistemicEvent {

    @Override
    public EpistemicEventType type() {
        return EpistemicEventType.OPPOSE;
    }

}