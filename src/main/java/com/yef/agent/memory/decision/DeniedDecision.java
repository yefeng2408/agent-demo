package com.yef.agent.memory.decision;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.decision.biz.DominantDecision;
import java.util.List;
import java.util.Optional;

/**
 * 确认不成立的裁决
 *
 * 例如：
 * - “我不拥有特斯拉”
 */
public record DeniedDecision(
        ClaimEvidence claim,
        DecisionReason reason
) implements DominantDecision {

    @Override
    public boolean isFinal() {
        return true;
    }

    @Override
    public Optional<ClaimEvidence> decidedClaim() {
        return Optional.of(claim);
    }

    @Override
    public List<ClaimEvidence> candidates() {
        return List.of(claim);
    }

    @Override
    public String getType() {
        return "OVERRIDDEN";
    }
}