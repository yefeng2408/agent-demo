package com.yef.agent.memory.selfHealing.builder;

import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.selfHealing.SelfCorrectionContext;

public interface SelfCorrectionContextBuilder {

    SelfCorrectionContext from(EpistemicEvent event);

}
