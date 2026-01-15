package com.yef.agent.graph.answer;

import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import java.time.Instant;

public record ClaimEvidence(
        String subjectId,
        PredicateType predicate,
        String objectId,
        Quantifier quantifier,
        boolean polarity,
        double confidence,
        Source source,
        String batch,
        Instant updatedAt,
        int priority
) {}