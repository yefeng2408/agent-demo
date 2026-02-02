package com.yef.agent.memory.pipeline;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.eum.SemanticRelation;
import com.yef.agent.memory.event.EpistemicEventType;

/**
 * 上下文
 * EpistemicContext 同时承载了：
 * 	1.	输入事实（extracted）
 * 	2.	既有信念（dominant）
 * 	3.	可能发生博弈的“对立假设空间”（opposite）
 * 	summary：extracted未必和opposite是相同的五元组，但opposite一定和dominant相反
 * @param userId
 * @param dominant
 * @param extracted
 * @param opposite
 */
public record EpistemicContext(

        String userId,

        // 被挑战的既有主张
        Citation dominant,

        // 本轮新输入抽取出的 relation
        ExtractedRelation extracted,

        // canonical 的对立 relation（仅在 OPPOSE 时存在）
        ExtractedRelation opposite,

        // ✅ 新增：语义关系判断结果
        SemanticRelation semanticRelation

) {

    public static EpistemicContext fromAnswer(
            String userId,
            //历史主张（被挑战者）
            AnswerResult answer,
            //当前新主张（挑战者）
            ExtractedRelation extracted,

            SemanticRelation semanticRelation
            ) {

        Citation dominant = answer.citations().get(0);

        ExtractedRelation opposite = null;
        if (extracted != null && extracted.polarity() != dominant.polarity()) {
            opposite = ExtractedRelation.getOppositeExtract(dominant);
        }

        return new EpistemicContext(
                userId,
                dominant,  // was challenged
                extracted, // challenger
                opposite,
                semanticRelation
        );
    }

}