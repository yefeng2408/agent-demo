package com.yef.agent.memory.explain.biz;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.decision.biz.DominantDecision;
import com.yef.agent.memory.vo.DominantClaimVO;

import java.util.List;

public interface ExplainableAnswerBuilder {

    AnswerResult buildOwnsAnswer(
            String userId,
            String objectName,
            DominantDecision decision,
            List<ClaimEvidence> candidates
    );

    AnswerResult build(String userId, ExtractedRelation relation, DominantClaimVO dom);
}