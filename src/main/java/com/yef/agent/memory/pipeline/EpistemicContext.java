package com.yef.agent.memory.pipeline;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.eum.InteractionType;
import com.yef.agent.graph.eum.SemanticRelation;
import com.yef.agent.memory.event.EpistemicEventType;

import java.util.Objects;

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

        AnswerResult result,

        // 被挑战的既有主张
        Citation dominant,

        // 本轮新输入抽取出的 relation
        ExtractedRelation extracted,

        // canonical 的对立 relation（仅在 OPPOSE 时存在）
        ExtractedRelation opposite,

        // ✅ 新增：语义关系判断结果
        SemanticRelation semanticRelation,
        //根据用户当前提问内容是去判断话术类型？【提问、陈述、挑战现有存在的claim（dominant）】
        InteractionType type

) {

    public static EpistemicContext fromAnswer(
            String userId,
            //历史主张（被挑战者）
            AnswerResult answer,
            //当前新主张（挑战者）= 等于用户此时发出的内容抽取的到的newExtractedRelation
            ExtractedRelation extracted,
            SemanticRelation semanticRelation,
            InteractionType type

            ) {

        Citation dominant = answer.citations().get(0);

        ExtractedRelation opposite = null;
        if (extracted != null && extracted.polarity() != dominant.polarity()) {
            opposite = ExtractedRelation.getOppositeExtract(dominant);
        }

        return new EpistemicContext(
                userId,
                answer,
                dominant,  // was challenged
                extracted, // challenger
                opposite,
                semanticRelation,
                type
        );
    }


    public boolean isRepeatedAssertion(EpistemicContext ctx,Citation dominant) {
        // 1️⃣ 只关心 ASSERT
        if (ctx.type() != InteractionType.ASSERT) {
            return false;
        }

        // 2️⃣ 必须是同向 SUPPORT
        if (ctx.semanticRelation() != SemanticRelation.SUPPORT) {
            return false;
        }

        // 3️⃣ 必须已有 dominant claim
        if (dominant == null) {
            return false;
        }

        // 4️⃣ 当前 statement 必须和 dominant 是“同一个 claim”
        return isSameClaim(dominant, ctx.extracted());
    }

    private boolean isSameClaim(Citation dominant, ExtractedRelation r) {
        return Objects.equals(dominant.subjectId(), r.subjectId())
                && Objects.equals(dominant.predicate(), r.predicateType().name())
                && Objects.equals(dominant.objectId(), r.objectId())
                && Objects.equals(dominant.quantifier(), r.quantifier().name())
                && dominant.polarity() == r.polarity();
    }


}