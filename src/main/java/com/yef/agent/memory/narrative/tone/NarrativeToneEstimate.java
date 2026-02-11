package com.yef.agent.memory.narrative.tone;

import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeTone;

public class NarrativeToneEstimate {

    public NarrativeTone decideTone(DominantNarrativeScore score) {

        if (score.status() == EpistemicStatus.DENIED) {
            return NarrativeTone.UNCERTAIN;
        }

        if (score.confidence() > 0.85 && !score.recentlyChallenged()) {
            return NarrativeTone.ASSERTIVE;
        }

        if (score.confidence() > 0.65) {
            return NarrativeTone.CONFIDENT;
        }

        if (score.confidence() > 0.4) {
            return NarrativeTone.CAUTIOUS;
        }

        return NarrativeTone.UNCERTAIN;
    }
}
