package com.yef.agent.memory.decision;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.BeliefState;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.decision.biz.DominantClaimSelector;
import com.yef.agent.memory.decision.biz.DominantDecision;
import com.yef.agent.memory.momentum.BeliefStoreGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class DefaultDominantClaimSelector implements DominantClaimSelector {

    @Autowired
    private KeyCodec keyCodec;
    @Autowired
    BeliefStoreGateway beliefStoreGateway;

    //@DeprecatedLogic("GraphDominant")
    @Override
    public DominantDecision select(List<ClaimEvidence> candidates) {
        ClaimEvidence c = candidates.get(0);
        String slotKey =keyCodec.buildSlotKey(
                c.subjectId(),c.predicate(),
                c.objectId(),c.quantifier());
        // ⭐ STEP 1 — Momentum First
        Optional<BeliefState> belief = beliefStoreGateway.load(slotKey);

        if (belief.isPresent()) {
            String dominant = belief.get().getDominantClaimKey();

            if (dominant != null) {

                Optional<ClaimEvidence> decided =
                        candidates.stream()
                                .filter(e ->
                                        keyCodec.buildEvidenceKey(e).equals(dominant))
                                .findFirst();

                if (decided.isPresent()) {
                    ClaimEvidence e = decided.get();

                    if (e.polarity()) {
                        return new ConfirmedDecision(
                                e,
                                DecisionReason.MOMENTUM_DOMINANT
                        );
                    } else {
                        return new DeniedDecision(
                                e,
                                DecisionReason.MOMENTUM_DOMINANT
                        );
                    }
                }
            }
        }
        // ⭐ STEP 2 — fallback（仅兜底）
        ClaimEvidence best = candidates.stream()
                .max(Comparator.comparingDouble(ClaimEvidence::confidence))
                .orElse(null);

        if (best == null) {
            return new UncertainDecision(
                    candidates,
                    DecisionReason.NO_BELIEFSTATE
            );
        }

        if (best.polarity()) {
            return new ConfirmedDecision(
                    best,
                    DecisionReason.FALLBACK_CONFIDENCE
            );
        } else {
            return new DeniedDecision(
                    best,
                    DecisionReason.FALLBACK_CONFIDENCE
            );
        }
    }
}