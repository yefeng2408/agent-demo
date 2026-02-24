package com.yef.agent.memory.narrative.cue.impl;

import com.yef.agent.memory.narrative.NarrativeRenderContext;
import com.yef.agent.memory.narrative.cue.NarrativeCue;

public class UncertaintyCue implements NarrativeCue {
    @Override
    public void apply(NarrativeRenderContext ctx) {
        ctx.appendSuffix("（当前置信度较低）");
    }
}
