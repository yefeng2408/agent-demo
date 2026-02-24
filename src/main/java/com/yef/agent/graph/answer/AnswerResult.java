package com.yef.agent.graph.answer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.memory.explain.ExplanationItem;
import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeTone;
import com.yef.agent.memory.decision.biz.DominantDecision;
import com.yef.agent.memory.vo.DominantClaimVO;

import java.util.List;


@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerResult(
        boolean answered,
        String answer,
        ExtractedRelation relation,     // ✅ 决策（唯一）
        List<Citation> citations,       // ✅ 证据（多条）
        List<ExplanationItem> explanations,  // 可选
        DominantDecision decision
) {

    public static AnswerResult unanswered() {
        return new AnswerResult(
                false,
                null,
                null,
                List.of(),
                null,
                null
        );
    }

    public static AnswerResult ok(
            String answer,
            ExtractedRelation relation,
            List<Citation> citations,
            List<ExplanationItem> explanations,
            DominantDecision decision
    ) {
        return new AnswerResult(
                true,
                answer,
                relation,
                citations,
                explanations,
                decision
        );
    }

    public static AnswerResult pk(
            String text,
            List<Citation> citations,
            NarrativeTone tone,
            DominantNarrativeScore score
    ) {
        return new AnswerResult(
                true,
                text,
                null,     // relation 暂无
                citations,
                null,     // explanation 暂无
                null      // decision 暂无
        );
    }


    public static AnswerResult fromDominant(DominantClaimVO dom) {
        if (dom == null || dom.claim() == null) {
            return unanswered();
        }
        ClaimEvidence claim = dom.claim();
        ExtractedRelation relation =
                ExtractedRelation.forUserStatement(
                        claim.subjectId(),
                        claim.predicate(),
                        claim.objectId(),
                        claim.quantifier(),
                        claim.polarity()
                );

        return new AnswerResult(
                true,
                relation.toReadableText(),
                relation,
                List.of(Citation.from(dom.claim())),   // ⭐ 必须保留
                null,
                null
        );
    }
}