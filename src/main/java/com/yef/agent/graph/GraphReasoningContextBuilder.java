/*
package com.yef.agent.graph;

import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import org.springframework.stereotype.Component;

@Component
public class GraphReasoningContextBuilder {

    public String build(String userId, String question, AnswerResult ar) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个只根据【Graph Evidence】回答的助手。\n");
        sb.append("禁止凭空编造；如果证据不足，必须说“不确定/需要补充信息”。\n\n");

        sb.append("【User】").append(userId).append("\n");
        sb.append("【Question】").append(question).append("\n\n");

        if (!ar.answered()) {
            sb.append("【Current Answer】未命中图证据，应该提出澄清问题。\n");
            return sb.toString();
        }

        sb.append("【Graph Conclusion】\n");
        sb.append("- predicate: ").append(ar.relation().predicateType().name()).append("\n");
        sb.append("- answer: ").append(ar.answer()).append("\n");
        sb.append("- confidence: ").append(String.format("%.2f", ar.relation().confidence())).append("\n\n");

        sb.append("【Graph Evidence (citations)】\n");
        for (Citation c : ar.citations()) {
            sb.append("- ")
                    .append(c.predicate()).append(" ")
                    .append(c.subjectId()).append(" ")
                    .append(c.quantifier()).append(" ")
                    .append(c.polarity() ? "TRUE" : "FALSE")
                    .append(" object=").append(c.objectId())
                    .append(" conf=").append(String.format("%.2f", c.confidence()))
                    .append(" source=").append(c.source())
                    .append("\n");
        }

        sb.append("\n【Output Requirements】\n");
        sb.append("1) 用自然中文改写答案（1-2 句）。\n");
        sb.append("2) 语气必须反映置信度：高->“我目前很确定/根据你之前的陈述”；中->“我倾向于”；低->“我不确定”。\n");
        sb.append("3) 如果存在冲突证据：要说明“你曾经说过A，但后来又说过B”。\n");
        sb.append("4) 如需补充信息，最多追问1个关键问题。\n");

        return sb.toString();
    }
}*/
