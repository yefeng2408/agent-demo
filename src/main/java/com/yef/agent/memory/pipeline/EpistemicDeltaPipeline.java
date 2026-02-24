package com.yef.agent.memory.pipeline;

import com.yef.agent.memory.event.EpistemicEvent;

public interface EpistemicDeltaPipeline {

    EpistemicEvent execute(EpistemicContext ctx);

}