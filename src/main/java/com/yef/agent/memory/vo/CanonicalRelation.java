package com.yef.agent.memory.vo;

import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;

public record CanonicalRelation(
        String subjectId,
        PredicateType predicate,
        String objectId,        // 归一后的 objectId（禁止 any/unknown）
        Quantifier quantifier,  // ONE/ANY（归一后的量词）
        boolean polarity,       // true=ASSERTED false=DENIED
        double confidence,
        Source source
) {}