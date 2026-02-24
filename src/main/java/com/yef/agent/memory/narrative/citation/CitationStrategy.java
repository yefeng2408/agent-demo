package com.yef.agent.memory.narrative.citation;

import com.yef.agent.graph.answer.Citation;
import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;

public interface CitationStrategy {
    List<Citation> build(DominantClaimVO dom,
                         NarrativeContext ctx,
                         DominantNarrativeScore score,
                         List<NarrativeCue> cues);
    int priority();
}