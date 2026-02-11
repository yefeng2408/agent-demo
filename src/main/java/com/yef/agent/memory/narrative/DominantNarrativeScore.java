package com.yef.agent.memory.narrative;

import com.yef.agent.memory.EpistemicStatus;
import java.time.Duration;

public record DominantNarrativeScore(
        double effectiveConfidence,
        EpistemicStatus status,
        Duration dominanceAge,
        boolean recentlyChallenged
) {}
