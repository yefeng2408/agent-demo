package com.yef.agent.component;

import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;

public record ClaimEvidenceKey(
        String subjectId,
        PredicateType predicate,
        String objectId,
        Quantifier quantifier,
        boolean polarity
) {}
