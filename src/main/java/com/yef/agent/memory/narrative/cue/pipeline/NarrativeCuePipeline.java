package com.yef.agent.memory.narrative.cue.pipeline;

import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.narrative.cue.strategy.NarrativeCueStrategy;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;
import java.util.Optional;

public final class NarrativeCuePipeline {

    private final List<NarrativeCueStrategy> strategies;

    public NarrativeCuePipeline(List<NarrativeCueStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public List<NarrativeCue> collect(DominantClaimVO dom, NarrativeContext ctx, DominantNarrativeScore score) {
        return strategies.stream()
                .sorted((a,b) -> Integer.compare(a.order(), b.order()))
                .map(s -> s.buildCue(dom, ctx, score))
                .flatMap(Optional::stream)
                .toList();
    }
}
