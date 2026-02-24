package com.yef.agent.memory.narrative.renderer;

import com.yef.agent.graph.answer.ClaimEvidence;
import org.springframework.stereotype.Component;

@Component
public class DefaultFactRenderer implements FactRenderer {

    @Override
    public String render(ClaimEvidence claim) {
        return claim.subjectId()+ " " +
                claim.predicate().name() + " " +
                claim.objectId();
    }
}