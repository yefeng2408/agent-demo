package com.yef.agent.memory.narrative.citation.impl;

import com.yef.agent.graph.answer.Citation;
import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.citation.CitationStrategy;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;

public class DefaultSingleCitationStrategyImpl implements CitationStrategy {

    @Override
    public List<Citation> build(DominantClaimVO dom,
                                NarrativeContext ctx,
                                DominantNarrativeScore score,
                                List<NarrativeCue> cues) {

        return List.of(Citation.from(dom.claim()));
    }

    @Override
    public int priority() {
        return 1;
    }

}
