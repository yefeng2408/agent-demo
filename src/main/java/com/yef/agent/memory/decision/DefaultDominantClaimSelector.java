package com.yef.agent.memory.decision;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.decision.biz.DominantClaimSelector;
import com.yef.agent.memory.decision.biz.DominantDecision;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DefaultDominantClaimSelector implements DominantClaimSelector {

    @Override
    public DominantDecision select(List<ClaimEvidence> candidates) {

        if (candidates == null || candidates.isEmpty()) {
            return new UncertainDecision(List.of(), DecisionReason.NO_CLAIM);
        }

        // 1️⃣ 按 epistemicStatus 分组
        Map<EpistemicStatus, List<ClaimEvidence>> byStatus = candidates.stream()
                        .collect(Collectors.groupingBy(
                                c -> c.epistemicStatus() == null
                                        ? EpistemicStatus.UNKNOWN
                                        : c.epistemicStatus()
                        ));

        // 2️⃣ CONFIRMED
        List<ClaimEvidence> confirmed = byStatus.get(EpistemicStatus.CONFIRMED);
        if (confirmed != null && confirmed.size() == 1) {
            return new ConfirmedDecision(confirmed.get(0),DecisionReason.CONFIRMED_SINGLE);
        }

        // 3️⃣ CONFIRMED 冲突（极少，但必须兜住）
        if (confirmed != null && confirmed.size() > 1) {
            return new UncertainDecision(
                    confirmed,
                    DecisionReason.MULTIPLE_CONFIRMED
            );
        }

        // 4️⃣ DENIED
        List<ClaimEvidence> denied = byStatus.get(EpistemicStatus.DENIED);
        if (denied != null && denied.size() == 1) {
            return new DeniedDecision(denied.get(0),DecisionReason.DENIED_SINGLE);
        }

        // 5️⃣ WEAKLY_SUPPORTED
        // 5️⃣ HYPOTHETICAL（弱支持，但不可裁决）
        List<ClaimEvidence> hypo = byStatus.get(EpistemicStatus.HYPOTHETICAL);
        if (hypo != null && hypo.size() == 1) {
            return new UncertainDecision(
                    hypo,
                    DecisionReason.HYPOTHETICAL_SINGLE
            );
        }
        if (hypo != null && hypo.size() > 1) {
            return new UncertainDecision(
                    hypo,
                    DecisionReason.HYPOTHETICAL_CONFLICT
            );
        }

        // 6️⃣ fallback：按 confidence 最大
        ClaimEvidence best = candidates.stream()
                        .max(Comparator.comparingDouble(ClaimEvidence::confidence))
                        .orElse(null);

        if (best == null) {
            return new UncertainDecision(candidates, DecisionReason.NO_BEST);
        }

        return new UncertainDecision(
                List.of(best),
                DecisionReason.LOW_CONFIDENCE_FALLBACK
        );
    }
}