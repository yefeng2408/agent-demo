package com.yef.agent.memory.narrative.renderer;

import com.yef.agent.graph.answer.ClaimEvidence;

public interface FactRenderer {

    String render(ClaimEvidence claim);

}
