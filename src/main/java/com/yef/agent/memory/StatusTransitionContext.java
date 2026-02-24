package com.yef.agent.memory;

public record StatusTransitionContext(
        String userId,
        EpistemicStatus from,
        EpistemicStatus to,
        double evidenceConfidence,
        int recentEvidenceCount
) {}