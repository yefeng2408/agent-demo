package com.yef.agent.graph;

import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;

public record ExtractedRelation(
        String subjectId,     // USER / PERSON:xxx / ORG:xxx
        PredicateType predicateType,  // 枚举，绝不自由文本
        String objectId,      // DOMAIN:xxx / ANY
        Quantifier quantifier,// ONE / ANY
        boolean polarity,     // true=肯定，false=否定
        double confidence,    // 0.0 ~ 1.0（语言确定性，不是事实真值）
        Source source         // USER_STATEMENT / SELF_CORRECTION / QUESTION
) {}