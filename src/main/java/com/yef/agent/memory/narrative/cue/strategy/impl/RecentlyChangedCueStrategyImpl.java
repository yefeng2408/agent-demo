package com.yef.agent.memory.narrative.cue.strategy.impl;

import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.narrative.cue.impl.RecentlyChangedCue;
import com.yef.agent.memory.narrative.cue.strategy.NarrativeCueStrategy;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.Optional;

public final class RecentlyChangedCueStrategyImpl implements NarrativeCueStrategy {

    @Override
    public Optional<NarrativeCue> buildCue(DominantClaimVO dom, NarrativeContext ctx, DominantNarrativeScore score) {
        return ctx.recentlyChallenged() ? Optional.of(new RecentlyChangedCue()) : Optional.empty();
    }

    @Override
    public int order() {
        return 10;
    }
}
