package com.yef.agent.graph.answer;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.memory.explain.ExplanationItem;
import com.yef.agent.memory.selector.ConfirmedDecision;
import com.yef.agent.memory.selector.DeniedDecision;
import com.yef.agent.memory.selector.UncertainDecision;
import com.yef.agent.memory.selector.biz.DominantDecision;

import java.util.List;

public record AnswerResult(
        boolean answered,
        String answer,
        ExtractedRelation relation,     // ✅ 决策（唯一）
        List<Citation> citations,       // ✅ 证据（多条）
        List<ExplanationItem> explanations,  // 可选
        DominantDecision decision

) {

    public static AnswerResult unanswered() {
        return new AnswerResult(false, null, null, List.of(), null,null);
    }

    public static AnswerResult ok(
            String answer,
            ExtractedRelation relation,
            List<Citation> citations,
            List<ExplanationItem> explanations,
            DominantDecision decision) {
        return new AnswerResult(true, answer, relation, citations, explanations,decision);
    }


}