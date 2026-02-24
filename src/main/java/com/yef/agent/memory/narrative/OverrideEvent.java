package com.yef.agent.memory.narrative;

import java.time.Instant;

public record OverrideEvent(
        String fromClaimId,
        String toClaimId,
        Instant at,
        String reason
) {}