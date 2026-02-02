package com.yef.agent.memory.selfHealing;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.selfHealing.mutation.ClaimMutation;

import java.util.List;

public record SelfCorrectionRule(
        String ruleId,
        int priority,
        java.util.function.BiPredicate<ClaimEvidence, ClaimEvidence> when,
        java.util.function.BiFunction<ClaimEvidence, ClaimEvidence, List<ClaimMutation>> then
) {}