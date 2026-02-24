package com.yef.agent.memory.pipeline.eventRouter;

import com.yef.agent.memory.event.factory.EpistemicEventFactory;
import com.yef.agent.memory.pipeline.EpistemicContext;

public interface EpistemicEventRouter {

    EpistemicEventFactory route(EpistemicContext ctx);

}