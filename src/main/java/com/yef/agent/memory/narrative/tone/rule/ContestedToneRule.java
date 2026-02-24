package com.yef.agent.memory.narrative.tone.rule;

import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.NarrativeTone;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.narrative.cue.NarrativeCueEnum;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;
import java.util.Optional;

public final class ContestedToneRule implements NarrativeToneRule {

    @Override
    public Optional<NarrativeTone> decide(DominantClaimVO dom,
                                          NarrativeContext ctx,
                                          DominantNarrativeScore score,
                                          List<NarrativeCue> cues) {
        return cues.contains(NarrativeCueEnum.CONTESTED_CUE) ? Optional.of(NarrativeTone.UNCERTAIN) : Optional.empty();
    }

    @Override
    public int priority() {
        return 100;
    }
}