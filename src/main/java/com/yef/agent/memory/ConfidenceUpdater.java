package com.yef.agent.memory;

import org.springframework.stereotype.Component;

import static com.yef.agent.memory.EpistemicStatus.CONFIRMED;
import static com.yef.agent.memory.EpistemicStatus.DENIED;

@Component
public class ConfidenceUpdater {

    public double apply(
            double current,
            EpistemicStatus from,
            EpistemicStatus to,
            double evidenceScore
    ) {
        if (from == CONFIRMED && to == DENIED) {
            return Math.max(current - evidenceScore * 0.3, 0.1);
        }
        if (from == DENIED && to == CONFIRMED) {
            return Math.min(current + evidenceScore * 0.4, 0.9);
        }
        return current;
    }
}