package com.yef.agent.graph.extract;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.eum.InteractionType;

public class EpistemicExtractContext {

    private final String userId;
    private final String rawText;
    private final InteractionType interactionType;
    // ASK: 为空
    // ASSERT / CHALLENGE: 一定存在
    private final ExtractedRelation extractedRelation;
    // 是否允许进入博弈 / 状态迁移
    private final boolean allowConflict;
    // 是否允许写 Claim
    private final boolean allowClaimWrite;


    public EpistemicExtractContext(String userId,
                                   String rawText,
                                   InteractionType interactionType,
                                   ExtractedRelation extractedRelation,
                                   boolean allowConflict,
                                   boolean allowClaimWrite
                                  ) {
        this.userId = userId;
        this.rawText = rawText;
        this.interactionType = interactionType;
        this.extractedRelation = extractedRelation;
        this.allowConflict = allowConflict;
        this.allowClaimWrite = allowClaimWrite;
    }


    public static EpistemicExtractContext fromInput(
            String userId,
            String msg,
            InteractionClassifier.ClassificationResult classify,
            ExtractedRelation newExtractedRelation
    ) {
        return switch (classify.interactionType()) {

            case ASK -> EpistemicExtractContext.askOnly(userId, msg);

            case ASSERT -> EpistemicExtractContext.assertion(
                    userId,
                    msg,
                    newExtractedRelation
            );

            case CHALLENGE -> EpistemicExtractContext.challenge(
                    userId,
                    msg,
                    newExtractedRelation
            );

        };

    }

    //严格只读
    public static EpistemicExtractContext askOnly(String userId, String msg) {
        return new EpistemicExtractContext(
                userId,
                msg,
                InteractionType.ASK,
                null,
                false,   //不进入博弈
                false    // 不写 Claim
        );
    }

    //写 Claim，但默认不触发博弈
    public static EpistemicExtractContext assertion(
            String userId,
            String msg,
            ExtractedRelation relation) {
        if (relation == null) {
            throw new IllegalArgumentException("ASSERT must have ExtractedRelation");
        }

        return new EpistemicExtractContext(
                userId,
                msg,
                InteractionType.ASSERT,
                relation,
                false,  // 默认不进入对立博弈
                true    // 写 Claim
        );
    }

    //强制进入博弈
    public static EpistemicExtractContext challenge(
            String userId,
            String msg,
            ExtractedRelation relation) {
        if (relation == null) {
            throw new IllegalArgumentException("CHALLENGE must have ExtractedRelation");
        }

        return new EpistemicExtractContext(
                userId,
                msg,
                InteractionType.CHALLENGE,
                relation,
                true,   // ✅ 必须进入博弈
                true    // ✅ 写 Claim
        );
    }



    public String getUserId() {
        return userId;
    }

    public String getRawText() {
        return rawText;
    }

    public InteractionType getInteractionType() {
        return interactionType;
    }

    public ExtractedRelation getExtractedRelation() {
        return extractedRelation;
    }

    public boolean isAllowConflict() {
        return allowConflict;
    }

    public boolean isAllowClaimWrite() {
        return allowClaimWrite;
    }

}
