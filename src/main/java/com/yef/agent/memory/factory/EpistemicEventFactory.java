package com.yef.agent.memory.factory;

import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.pipeline.EpistemicContext;
import java.util.List;

public interface EpistemicEventFactory {

    EpistemicEvent build(EpistemicContext ctx, List<ClaimDelta> deltas);
}
