package com.yef.agent.graph;

import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.ClaimGeneration;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import lombok.AllArgsConstructor;
// Claim Semantic Key（语义主键）
//(subjectId, predicate, objectId, quantifier, polarity)
public record ExtractedRelation(
        String subjectId,     // USER / PERSON:xxx / ORG:xxx
        PredicateType predicateType,  // 枚举，绝不自由文本
        String objectId,      // DOMAIN:xxx / ANY
        Quantifier quantifier,// ONE / ANY
        boolean polarity,     // true=肯定，false=否定
        double confidence,    // 0.0 ~ 1.0（语言确定性，不是事实真值）
        Source source,         // USER_STATEMENT / SELF_CORRECTION / QUESTION
        ClaimGeneration generation
) {


    public ClaimGeneration generation() {
        return ClaimGeneration.V3;
    }

    public boolean isLegacy() {
        return generation.isLegacy();
    }

    public static ExtractedRelation fromEvidence(ClaimEvidence e, Source source) {
        return new ExtractedRelation(
                e.subjectId(),
                e.predicate(),
                e.objectId(),
                e.quantifier(),
                e.polarity(),
                normalizeConfidence(e.confidence()),
                source,
                ClaimGeneration.V3

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

    public static ExtractedRelation forUserStatement(
            String subjectId,
            PredicateType predicateType,
            String objectId,
            Quantifier quantifier,
            boolean polarity) {
        return new ExtractedRelation(
                subjectId,
                predicateType,
                objectId,
                quantifier,
                polarity,
                0.5,                      // ✅ 默认初始置信度
                Source.USER_STATEMENT,    // ✅ 来自用户声明
                ClaimGeneration.V3        // ✅ v3 认知系统
        );
    }


    public static ExtractedRelation relationFromDominant(Citation dominant) {
        return ExtractedRelation.forSystemEvolution(dominant);
    }

    /**
     * 将citation对象转化为演化后的语义对象
     * @param citation
     * @return
     */
    public static ExtractedRelation forSystemEvolution(Citation citation) {
        return new ExtractedRelation(
                citation.subjectId(),
                PredicateType.valueOf(citation.predicate()),
                citation.objectId(),
                Quantifier.valueOf(citation.quantifier()),
                citation.polarity(),
                citation.confidence(),
                Source.EVOLUTION,    // ✅ 来自演化
                ClaimGeneration.V3   // ✅ v3 认知系统
        );
    }

    public static ExtractedRelation getOppositeExtract(Citation dominant) {
        return ExtractedRelation.forOppositeExtract(dominant);
    }

    public static ExtractedRelation forOppositeExtract(Citation citation) {
        return new ExtractedRelation(
                citation.subjectId(),
                PredicateType.valueOf(citation.predicate()),
                citation.objectId(),
                Quantifier.valueOf(citation.quantifier()),
                !citation.polarity(),
                citation.confidence(),
                Source.EVOLUTION,    // ✅ 来自演化
                ClaimGeneration.V3   // ✅ v3 认知系统
        );
    }




}