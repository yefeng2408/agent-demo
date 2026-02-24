package com.yef.agent.memory.event.factory;

import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.event.EpistemicEventType;
import com.yef.agent.memory.pipeline.EpistemicContext;
import java.util.List;

public interface EpistemicEventFactory {

    EpistemicEventType supportsType();   //负责的事件类型

    EpistemicEvent build(EpistemicContext ctx, List<ClaimDelta> deltas);
}
