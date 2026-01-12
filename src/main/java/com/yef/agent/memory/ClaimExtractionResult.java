package com.yef.agent.memory;

import java.util.List;

/**
 * LLM 输出的「认知主张抽取结果」
 * 注意：这是 belief，不是事实
 */
public record ClaimExtractionResult(
        boolean hasClaims,
        List<Claim> claims
) {

    public record Claim(
            String proposition,      // 结构化命题：user_owns_car(Tesla)
            String surface,          // 人类可读描述
            String modality,         // assert / deny / hypothetical / question
            EpistemicStatus status,  // CONFIRMED / DENIED / HYPOTHETICAL / UNKNOWN
            double confidence        // 0.0 ~ 1.0
    ) {}
}