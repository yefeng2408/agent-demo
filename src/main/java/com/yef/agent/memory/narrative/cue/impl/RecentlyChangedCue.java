package com.yef.agent.memory.narrative.cue.impl;

import com.yef.agent.memory.narrative.NarrativeRenderContext;
import com.yef.agent.memory.narrative.cue.NarrativeCue;

public final class RecentlyChangedCue implements NarrativeCue {

    @Override
    public void apply(NarrativeRenderContext ctx) {
        ctx.appendPrefix("🆕 最近发生变化：");
    }
}