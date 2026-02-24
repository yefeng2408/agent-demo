package com.yef.agent.memory.narrative.cue.impl;

import com.yef.agent.memory.narrative.NarrativeRenderContext;
import com.yef.agent.memory.narrative.cue.NarrativeCue;

public class ContestedCue implements NarrativeCue {

    @Override
    public void apply(NarrativeRenderContext ctx) {
        ctx.appendPrefix("⚠️ 存在争议：");
    }
}
