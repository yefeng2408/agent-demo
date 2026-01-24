package com.yef.agent.memory.event;

import com.yef.agent.memory.ClaimDelta;
import java.time.Instant;
import java.util.List;

public record SupportEvent(
        String eventId,
        String userId,
        Instant at,
        String evidenceKey,
        String reason,
        String supportedClaimKey,
        double delta
) implements EpistemicEvent {

    @Override
    public EpistemicEventType type() {
        return EpistemicEventType.SUPPORT;
    }

    @Override
    public List<ClaimDelta> deltas() {
        return List.of(
                new ClaimDelta(supportedClaimKey, delta)
        );
    }

}