package com.yef.agent.memory.selfHealing;

import com.yef.agent.memory.EpistemicStatus;

/**
 * 明确状态迁移（极少用，但必须存在）
 *
 * 只在非常确定的语义场景才出现，例如：
 * 	•	用户明确说：“我之前说错了”
 * 	•	明确时间锚定的 self-correction
 *
 * @param claimId
 * @param newStatus
 */
public record StatusOverride(
        String claimId,
        EpistemicStatus newStatus
) implements ClaimMutation {}