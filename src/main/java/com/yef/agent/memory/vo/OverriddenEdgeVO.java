package com.yef.agent.memory.vo;

import java.time.Instant;

public record OverriddenEdgeVO(
        String fromClaimId,
        String toClaimId,
        String reason,
        Instant at
) {}