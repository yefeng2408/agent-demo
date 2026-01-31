package com.yef.agent.memory.explain.biz;

import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.selector.biz.DominantDecision;
import java.util.List;

public interface ExplainableAnswerBuilder {

    AnswerResult buildOwnsAnswer(
            String userId,
            String objectName,
            DominantDecision decision,
            List<ClaimEvidence> candidates
    );

}