package com.yef.agent.memory.pipeline;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;

/**
 * 上下文，类似于netty的ChannelHandlerContext；Pipeline是结构，Context是数据
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

        // 当前图中被挑战的主张（已有 claim）
        Citation dominant,

        // 用户刚刚说的话。语义：本轮输入中，从自然语言抽取出的“原始立场”。relation（trigger）
        /** 本次输入形成的“挑战立场” */
        ExtractedRelation extracted,

        // 若是 OPPOSE，这里是 polarity 相反的 relation
        // 若是 SUPPORT / NEUTRAL，可以为 null
        /** 为博弈准备的对立 claim（polarity 翻转） */

        /**
         *  A canonical counterfactual relation constructed for epistemic conflict.
         *  This represents the structural opposite of the dominant claim,
         *  independent of how the user phrased the input.
         */
        //this object is from method of ExtractedRelation.getOppositeExtract(dominant)
        ExtractedRelation opposite
) {

    public static EpistemicContext fromAnswer(
            String userId,
            //历史主张（被挑战者）
            AnswerResult answer,
            //当前新主张（挑战者）
            ExtractedRelation extracted) {

        Citation dominant = answer.citations().get(0);

        ExtractedRelation opposite = null;
        if (extracted != null && extracted.polarity() != dominant.polarity()) {
            opposite = ExtractedRelation.getOppositeExtract(dominant);
        }

        return new EpistemicContext(
                userId,
                dominant,  // was challenged
                extracted, // challenger
                opposite
        );
    }

}