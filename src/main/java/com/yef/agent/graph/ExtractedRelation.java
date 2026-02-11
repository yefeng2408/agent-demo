package com.yef.agent.graph;

import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.*;


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
                ClaimGeneration.V3       // ✅ v3 认知系统
        );
    }


    public static ExtractedRelation getOppositeExtract(Citation dominant) {
        return new ExtractedRelation(
                dominant.subjectId(),
                PredicateType.valueOf(dominant.predicate()),
                dominant.objectId(),
                Quantifier.valueOf(dominant.quantifier()),
                !dominant.polarity(),
                dominant.confidence(),
                Source.EVOLUTION,    // ✅ 来自演化
                ClaimGeneration.V3   // ✅ v3 认知系统
        );
    }



}