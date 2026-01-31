package com.yef.agent.memory.selector;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.selector.biz.DominantDecision;
import java.util.List;
import java.util.Optional;

/**
 * 无法裁决（冲突 / 证据不足）
 *
 * 例如：
 * - 同时存在“拥有”和“不拥有”，且置信度接近
 */
public record UncertainDecision(
        List<ClaimEvidence> candidates,
        DecisionReason reason
) implements DominantDecision {

    @Override
    public boolean isFinal() {
        return false;
    }

    @Override
    public Optional<ClaimEvidence> decidedClaim() {
        return Optional.empty();
    }
}