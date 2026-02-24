package com.yef.agent.graph.answer;

import java.time.Instant;

public record Citation(
        String predicate,
        String subjectId,
        String objectId,
        String quantifier,
        boolean polarity,
        double confidence,
        String source,
        String batch,
        Instant updatedAt
) {


    public static Citation from(ClaimEvidence evidence) {
        return new Citation(
                evidence.predicate().name(),
                evidence.subjectId(),
                evidence.objectId(),
                evidence.quantifier().name(),
                evidence.polarity(),
                evidence.confidence(),
                evidence.source().name(),
                evidence.batch(),
                evidence.updatedAt()
                );
    }


    public String toExplainText() {
        return String.format(
                "- [%s] %s %s %s（置信度 %.2f，来源 %s，时间 %s）",
                predicate,
                polarity ? "确认" : "否认",
                quantifier,
                objectId,
                confidence,
                source,
                updatedAt == null ? "未知" : updatedAt
        );
    }
    
    

}