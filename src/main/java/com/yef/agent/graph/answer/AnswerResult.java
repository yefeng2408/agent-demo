package com.yef.agent.graph.answer;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.eum.PredicateType;

public record AnswerResult(
        boolean answered,
        String answer,
        PredicateType predicateUsed,
        double confidence,
        ExtractedRelation extractedRelation
) {
    public static AnswerResult ok(
            String answer,
            ExtractedRelation relation
    ) {
        return new AnswerResult(
                true,
                answer,
                relation.predicateType(),
                relation.confidence(),
                relation
        );
    }

    public static AnswerResult unanswered() {
        return new AnswerResult(false, null, null, 0.0, null);
    }
}