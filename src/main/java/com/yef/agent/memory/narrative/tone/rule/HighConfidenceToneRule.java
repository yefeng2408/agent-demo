package com.yef.agent.memory.narrative.tone.rule;

import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.NarrativeTone;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.narrative.cue.NarrativeCueEnum;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;
import java.util.Optional;

public final class HighConfidenceToneRule implements NarrativeToneRule {

    @Override
    public Optional<NarrativeTone> decide(DominantClaimVO dom,
                                          NarrativeContext ctx,
                                          DominantNarrativeScore score,
                                          List<NarrativeCue> cues) {

        return (score.effectiveConfidence() > 0.9 && !cues.contains(NarrativeCueEnum.UNCERTAINTY_CUE))
                ? Optional.of(NarrativeTone.ASSERTIVE)
                : Optional.empty();
    }

    @Override
    public int priority() {
        return 10;
    }

}