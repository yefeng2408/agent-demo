package com.yef.agent.graph;

import com.yef.agent.graph.answer.ClaimEvidence;
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
) {
    public static ExtractedRelation fromEvidence(ClaimEvidence e, Source source) {
        return new ExtractedRelation(
                e.subjectId(),
                e.predicate(),
                e.objectId(),
                e.quantifier(),
                e.polarity(),
                normalizeConfidence(e.confidence()),
                source
        );
    }

    private static double normalizeConfidence(double raw) {
        // 回答产生的新认知：永远 ≤ 原始证据
        return Math.min(raw, 0.7);
    }

    public String toReadableText() {
        String subject = subjectId;
        String predicate = predicateType.name();
        String quant = quantifier.name();
        String polarityText = polarity ? "ASSERTED" : "DENIED";

        return String.format(
                "DECISION: subject=%s, predicate=%s, object=%s, quantifier=%s, polarity=%s, confidence=%.2f, source=%s",
                subject,
                predicate,
                objectId,
                quant,
                polarityText,
                confidence,
                source.name()
        );
    }

}