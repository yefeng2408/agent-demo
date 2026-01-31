package com.yef.agent.memory.explain;

import com.yef.agent.memory.EpistemicStatus;
import java.time.Instant;

public record StatusTransitionSnapshot(
        EpistemicStatus from,
        EpistemicStatus to,
        String reason,
        Instant occurredAt
) {
}