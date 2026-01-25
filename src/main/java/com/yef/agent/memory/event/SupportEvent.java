package com.yef.agent.memory.event;

import com.yef.agent.memory.ClaimDelta;

import java.time.Instant;
import java.util.List;

public record SupportEvent(
        String eventId,
        String userId,
        Instant at,
        String triggerKey,
        String reason,
        String supportedClaimKey,
        List<ClaimDelta> deltas

) implements EpistemicEvent {

    @Override
    public EpistemicEventType type() {
        return EpistemicEventType.SUPPORT;
    }


}