package com.yef.agent.memory.selfHealing;

/**
 * 为“三态裁决”提前准备视角
 *
 * 它回答的是：
 *      “如果你现在非要我选，我更倾向哪一条，以及为什么”
 *      ⚠️ 再强调一次：这是 Hint，不是 Verdict
 *
 * @param claimId
 * @param reason
 */
public record DominantClaimHint (
        String claimId,
        String reason
){
}
