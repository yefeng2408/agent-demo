package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.BeliefState;
import com.yef.agent.memory.DeltaDirection;
import com.yef.agent.memory.intent.EpistemicIntent;
import com.yef.agent.memory.momentum.BeliefStoreGateway;
import com.yef.agent.memory.pipeline.TransitionReason;
import com.yef.agent.repository.impl.ClaimEvidenceRepositoryImpl;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.repository.impl.StatusTransitionRepositoryImpl;
import com.yef.agent.service.DominantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 状态迁移
 */
@Slf4j
@Component
public class StatusTransitionStage {

    private static final double PROMOTION_THRESHOLD = 0.80;

    //短时间内用户前后声明不一致。防止防震荡时间，可以调该数值。
    private static final Duration STATUS_COOLDOWN = Duration.ofSeconds(10);

    private final ClaimEvidenceRepositoryImpl claimEvidenceRepository;
    private final StatusTransitionRepositoryImpl statusTransitionRepository;
    private final KeyCodec keyCodec;
    private final DominantService dominantService;
    private final BeliefStoreGateway beliefStoreGateway;

    public StatusTransitionStage(ClaimEvidenceRepositoryImpl claimEvidenceRepository,
                                 StatusTransitionRepositoryImpl statusTransitionRepository,
                                 KeyCodec keyCodec, DominantService dominantService,
                                 BeliefStoreGateway beliefStoreGateway
    ) {
        this.claimEvidenceRepository = claimEvidenceRepository;
        this.statusTransitionRepository = statusTransitionRepository;
        this.keyCodec = keyCodec;
        this.dominantService = dominantService;
        this.beliefStoreGateway = beliefStoreGateway;
    }

    /**
     * OPPOSE  → 改 dominant。可能也会改变 epistemic
     * SUPPORT → 升 epistemic仅此而已
     *
     * @param userId
     * @param deltas
     */
    public void apply(String userId, List<ClaimDelta> deltas) {
        for (ClaimDelta delta : deltas) {
            if (delta.kind() == ClaimDelta.DeltaKind.CONFIDENCE_ONLY) {
                // ① 只做一件事：更新置信度和supportCount递增
                claimEvidenceRepository.updateConfidence(delta.claimKey(), delta.afterConfidence());
            }
        }
        // ② 再统一做「状态迁移判断」
        for (ClaimDelta delta : deltas) {
            handleStatusTransition(userId, delta);
        }
        // ③ 统一收口（dominant / override）
        afterAllDeltasApplied(userId, deltas);
    }


    /**
     * v4 FINAL VERSION
     * <p>
     * 作用：
     * 1️⃣ 按 claimSlot 聚合 delta
     * 2️⃣ 判断是否真实 challenge
     * 3️⃣ 重算 dominant
     * 4️⃣ 写 DOMINANT / OVERRIDE路径
     */
    public void afterAllDeltasApplied(String userId, List<ClaimDelta> deltas) {

        if (deltas == null || deltas.isEmpty()) {
            return;
        }

        Map<String, List<ClaimDelta>> slotMap = deltas.stream().collect(Collectors.groupingBy(d -> slotKeyOf(d.claimKey())));

        for (var entry : slotMap.entrySet()) {

            String slotKey = entry.getKey();

            List<ClaimDelta> slotDeltas = entry.getValue();

            processSlot(userId, slotKey, slotDeltas);
        }
    }

    private void processSlot(String userId,
                             String slotKey,
                             List<ClaimDelta> slotDeltas) {

        String anyKey = slotDeltas.get(0).claimKey();

        List<ClaimEvidence> all = claimEvidenceRepository.loadAllByClaimKey(userId, anyKey);

        boolean hasDominant = claimEvidenceRepository.hasDominant(userId, slotKey);

        // ========= bootstrap 过滤 =========
        if ((all == null || all.isEmpty())) {
            return;
        }

        if (all.size() < 2 && hasDominant) {
            return;
        }

        ClaimEvidence newDominant = all.stream()
                        .max(Comparator.comparingDouble(ClaimEvidence::confidence))
                        .orElse(null);

        if (newDominant == null) {
            return;
        }

        boolean realChallenge = isRealChallenge(slotDeltas);

        // ===== 1️⃣ 更新 DOMINANT =====
        updateDominant(userId, slotKey, newDominant, realChallenge);

        // ===== 2️⃣ Arbitration =====
        arbitrate(slotKey, realChallenge);

        // ===== 3️⃣ Momentum =====
        updateMomentum(slotKey, slotDeltas);
    }


    private void updateDominant(String userId,
                                String slotKey,
                                ClaimEvidence newDominant,
                                boolean realChallenge) {

        if (realChallenge) {
            claimEvidenceRepository.clearDominant(userId, slotKey);
        } else if (claimEvidenceRepository.hasDominant(userId, slotKey)) {
            return;
        }

        claimEvidenceRepository.writeDominant(
                userId,
                slotKey,
                keyCodec.buildEvidenceKey(newDominant),
                newDominant.confidence(),
                EpistemicStatus.HYPOTHETICAL,
                realChallenge ? "REAL_CHALLENGE" : "BOOTSTRAP"
        );
    }


    private void arbitrate(String slotKey, boolean realChallenge) {
        try {
            String beliefId = slotKey + "|" + System.currentTimeMillis();

            statusTransitionRepository.arbitrateDominant(
                    slotKey,
                    null,
                    beliefId,
                    realChallenge ? "REAL_CHALLENGE" : "BOOTSTRAP"
            );
        } catch (Exception e) {
            log.error("arbitrateDominant failed, slotKey={}", slotKey, e);
        }
    }


    private boolean isRealChallenge(List<ClaimDelta> deltas) {
        if (deltas.size() < 2) {
            return false;
        }
        double up = deltas.stream()
                .filter(d -> d.direction() == DeltaDirection.UP)
                .mapToDouble(ClaimDelta::delta)
                .sum();

        double down = deltas.stream()
                .filter(d -> d.direction() == DeltaDirection.DOWN)
                .mapToDouble(ClaimDelta::delta)
                .sum();

        // v4 曲线：挑战必须明显胜出
        return up > down * 1.25;
    }

    private void updateMomentum(String slotKey, List<ClaimDelta> slotDeltas) {

        try {
            Optional<BeliefState> beliefOpt = statusTransitionRepository.loadBeliefState(slotKey);
            if (beliefOpt.isEmpty()) {
                return;
            }

            BeliefState beliefState = beliefOpt.get();
            double momentum = beliefState.getMomentumP();

            for (ClaimDelta d : slotDeltas) {
                momentum = statusTransitionRepository.updateMomentum(momentum, d.direction());
            }
            statusTransitionRepository.updateBeliefMomentum(slotKey, momentum);
        } catch (Exception e) {
            log.error("momentum engine failed, slotKey={}", slotKey, e);
        }
    }

    private void handleStatusTransition(String userId, ClaimDelta delta) {
        if (delta.kind() != ClaimDelta.DeltaKind.CONFIDENCE_ONLY) {
            return;
        }
        String slotKey = keyCodec.slotKeyOfByClaimKey(delta.claimKey());
        //EpistemicStatus current = beliefStoreGateway.loadDominantStatus(slotKey);
        ClaimEvidence claim = claimEvidenceRepository.loadEvidenceClaimByClaimKey(userId, delta.claimKey());
        EpistemicStatus current = claim.epistemicStatus();
        if (current == null) {
            return;
        }
        if (!claimEvidenceRepository.hasDominant(userId, slotKey)) {
            return;
        }
        // ⭐ SUPPORT promotion ONLY
        if(delta.direction() == DeltaDirection.UP){
            if ( current == EpistemicStatus.HYPOTHETICAL && delta.afterConfidence() >= PROMOTION_THRESHOLD) {
                log.info("🔥 PROMOTION triggered, claimKey={}", delta.claimKey());
                statusTransitionRepository.writeStatusTransition(
                        userId,
                        delta.claimKey(),
                        EpistemicStatus.HYPOTHETICAL,
                        EpistemicStatus.CONFIRMED,
                        delta,
                        TransitionReason.CONFIDENCE_DELTA
                );
                claimEvidenceRepository.updateEpistemicStatus(delta.claimKey(), EpistemicStatus.CONFIRMED);
                return;
            }
        }else if(delta.direction() == DeltaDirection.DOWN){
            claimEvidenceRepository.updateEpistemicStatus(delta.claimKey(), EpistemicStatus.HYPOTHETICAL); //原先的dominant则降为 HYPOTHETICAL
        }


        ClaimEvidence evidence = claimEvidenceRepository.loadEvidenceClaimByClaimKey(userId, delta.claimKey());
        EpistemicStatus before = evidence.epistemicStatus();
        EpistemicStatus after = decideWithHysteresis(before, evidence.confidence());

        boolean semanticOverride =
                delta.intent() == EpistemicIntent.SELF_CORRECTION
                        && delta.intentConfidence() >= 0.7;

        boolean strongDelta = Math.abs(delta.afterConfidence() - delta.beforeConfidence()) >= 0.35;

        if (!semanticOverride) {
            if (inCooldown(evidence) && !strongDelta) return;
        }

        if (inCooldown(evidence) && !strongDelta && !semanticOverride) {
            return;
        }

        TransitionReason reason = semanticOverride
                ? TransitionReason.SELF_CORRECTION_OVERRIDE
                : TransitionReason.CONFIDENCE_DELTA;

        if (semanticOverride) {
            after = EpistemicStatus.CONFIRMED;
        }

        if (before == after || !semanticOverride) {
            return;
        }

        claimEvidenceRepository.updateEpistemicStatus(delta.claimKey(), after);

        statusTransitionRepository.writeStatusTransition(
                userId,
                delta.claimKey(),
                before,
                after,
                delta,
                reason
        );
        Optional<ClaimEvidence> oppositeOpt =
                claimEvidenceRepository.findBestOpposite(
                        userId,
                        slotKey,
                        evidence.polarity()
                );
        // 🔥 override rewrite
        // 9) override graph rewrite (only when semanticOverride)
        if (semanticOverride) {

            // ===== v3.11 Inertia Gate =====
            double inertiaThreshold = 0.45; // 0.35~0.55 可调
            double m = evidence.momentum();
            boolean passInertia = m >= inertiaThreshold;

            if (!passInertia) {
                log.info("[v3.11] semanticOverride blocked by inertia gate. momentum={} threshold={}",
                        m, inertiaThreshold);
                return; // 直接不 rewrite，不写 OVERRIDDEN_BY
            }

            try {

                if (oppositeOpt.isPresent()) {
                    ClaimEvidence opposite = oppositeOpt.get();

                    statusTransitionRepository.writeOverrideTransition(
                            userId,
                            slotKey,
                            keyCodec.buildEvidenceKey(opposite),   // from old belief
                            keyCodec.buildEvidenceKey(evidence),   // to new belief
                            delta.intentConfidence(),
                            delta
                    );
                }
            } catch (Exception e) {
                log.warn("[v3.11] override graph rewrite skipped", e);
            }
        }
        // ⭐⭐⭐ 必须补这一句（你现在缺失）
        boolean statusChanged = before != after;
        boolean momentumChanged = Math.abs(delta.afterConfidence() - delta.beforeConfidence()) > 0.01;

        if (statusChanged || momentumChanged) {
            dominantService.recomputeDominant(userId, delta.claimKey());
        }
        updateMomentumFromDelta(userId, evidence, delta);
    }


    private void updateMomentumFromDelta(
            String userId,
            ClaimEvidence evidence,
            ClaimDelta delta) {

        double oldMomentum = evidence.momentum();
        Instant last =
                evidence.lastMomentumAt() != null
                        ? evidence.lastMomentumAt()
                        : Instant.now();

        Instant now = Instant.now();
        long dtSeconds = Math.max(1,
                Duration.between(last, now).getSeconds());

        // 半衰期 6 小时
        double tau = 6 * 3600.0;
        double decay = Math.exp(-dtSeconds / tau);
        double signedDelta = delta.afterConfidence() - delta.beforeConfidence();
        double boost = 1.0;
        double newMomentum = oldMomentum * decay + signedDelta * boost;

        // clamp [-1,1]
        newMomentum = Math.max(-1.0,
                Math.min(1.0, newMomentum));
        claimEvidenceRepository.updateMomentum(
                delta.claimKey(),
                newMomentum,
                now
        );
    }

    private EpistemicStatus decideWithHysteresis(EpistemicStatus current, double conf) {
        current = current == null ? EpistemicStatus.UNKNOWN : current;

        switch (current) {
            case CONFIRMED -> {
                // 离开 CONFIRMED 要更“苛刻”
                if (conf < 0.75) return EpistemicStatus.HYPOTHETICAL;
                return EpistemicStatus.CONFIRMED;
            }
            case DENIED -> {
                if (conf > 0.35) return EpistemicStatus.HYPOTHETICAL;
                return EpistemicStatus.DENIED;
            }
            case HYPOTHETICAL, UNKNOWN -> {
                if (conf >= 0.85) return EpistemicStatus.CONFIRMED;
                if (conf <= 0.25) return EpistemicStatus.DENIED;
                return EpistemicStatus.HYPOTHETICAL;
            }
        }
        return EpistemicStatus.UNKNOWN;
    }

    //状态冷却窗口
    private boolean inCooldown(ClaimEvidence e) {
        if (e.lastStatusChangedAt() == null) return false;
        return e.lastStatusChangedAt().plus(STATUS_COOLDOWN).isAfter(Instant.now());
    }


    private ClaimDelta pickChallenger(List<ClaimDelta> deltas) {
        return deltas.stream()
                .filter(d -> d.direction() == DeltaDirection.UP)
                .max(Comparator.comparingDouble(ClaimDelta::afterConfidence))
                .orElse(null);
    }

    private ClaimDelta pickDominated(List<ClaimDelta> deltas) {
        return deltas.stream()
                .filter(d -> d.direction() == DeltaDirection.DOWN)
                .max(Comparator.comparingDouble(ClaimDelta::delta))
                .orElse(null);
    }

    private String slotKeyOf(String claimKey) {
        KeyCodec.DecodedKey k = keyCodec.decode(claimKey);
        return k.subjectId()
                + "|" + k.predicate().name()
                + "|" + k.objectId()
                + "|" + k.quantifier().name();
    }


    private boolean isOpposeGroup(List<ClaimDelta> group) {
        if (group.size() != 2) return false;

        var a = keyCodec.decode(group.get(0).claimKey());
        var b = keyCodec.decode(group.get(1).claimKey());

        //subjectId, predicate, objectId, quantifier 完全相同；且polarit一正一反的情况下才能判定为 OPPOSE
        boolean sameSlot =
                a.subjectId().equals(b.subjectId()) &&
                        a.predicate().equals(b.predicate()) &&
                        a.objectId().equals(b.objectId()) &&
                        a.quantifier().equals(b.quantifier());

        boolean oppositePolarity = a.polarity() != b.polarity();
        boolean upDown =
                (group.get(0).direction() != group.get(1).direction()) &&
                        (group.get(0).direction() == DeltaDirection.UP ||
                                group.get(1).direction() == DeltaDirection.UP); // 一正一反即可

        return sameSlot && oppositePolarity && upDown;
    }

}