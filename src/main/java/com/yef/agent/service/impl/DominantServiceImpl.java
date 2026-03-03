package com.yef.agent.service.impl;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.momentum.BeliefStoreGateway;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.memory.vo.DominantSnapshot;
import com.yef.agent.repository.ClaimEvidenceRepository;
import com.yef.agent.service.DominantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class DominantServiceImpl implements DominantService {

    private static final double CHALLENGE_THRESHOLD = 0.15;

    private static final long DOMINANT_COOLDOWN_SECONDS = 10; //TODO 建议60s

    @Autowired
    private ClaimEvidenceRepository claimEvidenceRepository;
    @Autowired
    private KeyCodec keyCodec;
    @Autowired
    private BeliefStoreGateway beliefStoreGateway;

    @Override
    public void recomputeDominant(String userId, String claimKey) {

        String slotKey = keyCodec.slotKeyOfByClaimKey(claimKey);

        List<ClaimEvidence> candidates = claimEvidenceRepository.loadActiveBySlot(userId, slotKey);

        if (candidates == null || candidates.isEmpty()) {
            log.info("[v3.10] no candidates for slot={}", slotKey);
            return;
        }

        // ========= 参数 =========
        double wC = 1.0;
        double wM = 0.35;
        double switchMargin = 0.08;

        // ========= 当前 dominant =========
        Optional<ClaimEvidence> currentOpt = claimEvidenceRepository.loadDominantClaim(userId, slotKey);

        // ========= Cooldown Guard (v3.12) =========
        Optional<Instant> lastSwitchAtOpt = claimEvidenceRepository.loadLastSwitchAt(userId, slotKey);

        if (lastSwitchAtOpt.isPresent()) {

            Instant lastSwitchAt = lastSwitchAtOpt.get();
            long seconds = Duration.between(lastSwitchAt, Instant.now()).getSeconds();

            if (seconds < DOMINANT_COOLDOWN_SECONDS) {
                log.info("[v3.12] dominant cooldown active, skip recompute slot={} remaining={}s",
                        slotKey, DOMINANT_COOLDOWN_SECONDS - seconds);
                return;
            }
        }

        ClaimEvidence currentDominant = currentOpt.orElse(null);
        log.info("------->recomputeDominant:currentDominant = {}", currentDominant);
        // ========= 计算评分 =========
        Comparator<ClaimEvidence> byScore = Comparator.comparingDouble(e ->
                dominantScore(e, wC, wM)
        );

        ClaimEvidence challenger =
                candidates.stream()
                        .max(byScore)
                        .orElse(null);

        if (challenger == null) {
            return;
        }

        double challengerScore = dominantScore(challenger, wC, wM);

        // ========= 如果当前没有 dominant =========
        EpistemicStatus epistemicStatus = beliefStoreGateway.loadDominantStatus(slotKey);
        if (currentDominant == null) {
            claimEvidenceRepository.writeDominant(
                    userId,
                    slotKey,
                    keyCodec.buildEvidenceKey(challenger),
                    challengerScore,
                    epistemicStatus,
                    "BOOTSTRAP"
            );

            log.info("[v3.10] dominant bootstrap -> {}", keyCodec.buildEvidenceKey(challenger));
            return;
        }

        double currentScore = dominantScore(currentDominant, wC, wM);

        // ========= Hysteresis Gate =========
        String challengerKey = keyCodec.buildEvidenceKey(challenger);
        String currentKey = keyCodec.buildEvidenceKey(currentDominant);
        boolean shouldSwitch =
                challengerKey.equals(currentKey)
                        ? false
                        : challengerScore >= currentScore + switchMargin;

        if (!shouldSwitch) {
            // 只是 touch dominance，不改 dominantSince
            claimEvidenceRepository.touchDominantMeta(
                    userId,
                    slotKey,
                    "NO_SWITCH_HYSTERESIS"
            );

            log.info("[v3.10] dominant hold (hysteresis) currentScore={} challengerScore={}", currentScore, challengerScore);
            return;
        }

        // ========= 真正切换 dominant =========
        claimEvidenceRepository.writeDominant(
                userId,
                slotKey,
                keyCodec.buildEvidenceKey(challenger),
                challengerScore,
                EpistemicStatus.HYPOTHETICAL,
                "DOMINANT_SWITCH"
        );

        log.info("[v3.10] dominant switched {} -> {}",
                keyCodec.buildEvidenceKey(currentDominant),
                keyCodec.buildEvidenceKey(challenger));
    }

    private double dominantScore(ClaimEvidence e, double wC, double wM) {
        double confidence = e.confidence();
        double momentum = e.momentum();

        double clampedMomentum = Math.max(-1.0, Math.min(1.0, momentum));
        return wC * confidence + wM * clampedMomentum;
    }



    @Override
    public Optional<DominantClaimVO> loadDominantView(String userId, String slotKey) {

        Optional<DominantSnapshot> snapOpt = claimEvidenceRepository.loadDominant(userId, slotKey);
        if (snapOpt.isEmpty()) {
            return Optional.empty();
        }

        DominantSnapshot snap = snapOpt.get();
        ClaimEvidence dom = snap.claim();

        Instant dominantSince = snap.since(); // ✅ 来自 BeliefState.since
        Duration age = dominantSince == null
                ? Duration.ZERO
                : Duration.between(dominantSince, Instant.now());

        // 你原来的 v3.12+ 语义：effective 用 claim.confidence（“当前置信度”）
        double effective = dom.confidence();

        // 但 UI 上你也想看“成为 dominant 当时的支持度”：来自 BeliefState.confidence
        //double supportAt = snap.supportConfidenceAt();

        // recentlyChallenged：我建议基于 snap.reason + dominantSince（更符合 dominant 语义）
        boolean recentlyChallenged =
                dominantSince != null
                        && Duration.between(dominantSince, Instant.now()).toMinutes() < 30
                        && "REAL_CHALLENGE".equals(snap.reason());

        return Optional.of(
                new DominantClaimVO(
                        slotKey,
                        dom,
                        supportAt,        // ✅ 原来你这里放 dom.confidence()，现在放 “dominant 时刻支持度”
                        effective,
                        dominantSince,
                        age,
                        dom.epistemicStatus(),
                        recentlyChallenged
                )
        );
    }


/*
    @Override
    public void recomputeAfterDeltas(EpistemicContext ctx, List<ClaimDelta> deltas) {
        String userId = ctx.userId();
        //dominant claimKey
        String claimKey = deltas.get(0).claimKey();

        Optional<ClaimEvidence> currentOpt =
                claimEvidenceRepository.loadDominant(userId, claimKey);

        // ===== 1️⃣ 单条 delta：support =====
        if (deltas.size() == 1) {

            ClaimDelta d = deltas.get(0);

            currentOpt.ifPresent(dom ->
                    claimEvidenceRepository.writeDominant(
                            userId,
                            claimKey,
                            //dom.id(),
                            keyCodec.buildEvidenceKey(dom),
                            d.afterConfidence(),
                            "support"
                    )
            );
            return;
        }

        // ===== 2️⃣ oppose：两条 delta =====
        ClaimEvidence challenger =
                claimEvidenceRepository.loadByKey(userId, claimKey);

        if (currentOpt.isEmpty()) {
            // 没有 dominant，challenger 直接上位
            claimEvidenceRepository.writeDominant(
                    userId,
                    claimKey,
                    //challenger.id(),
                    keyCodec.buildEvidenceKey(challenger),
                    challenger.confidence(),
                    "initial"
            );
            return;
        }

        ClaimEvidence dominant = currentOpt.get();

        // ===== 3️⃣ 判断是否是真挑战 =====
        if (isRealChallenge(dominant, challenger, deltas)) {

            List<Citation> candidates = ctx.result().citations();
            Citation winner = selectWinningChallenger(dominant, candidates);
            // ✅ 真正挑战成功
            claimEvidenceRepository.clearDominant(userId, claimKey);
            claimEvidenceRepository.writeDominant(
                    userId,
                    claimKey,
                    //challenger.id(),
                    keyCodec.buildEvidenceKey(challenger),
                    challenger.confidence(),
                    "challenge_win"
            );
            claimEvidenceRepository.writeOverriddenBy(
                    //dominant.id(),
                    keyCodec.buildEvidenceKey(dominant),
                    //challenger.id(),
                    keyCodec.buildEvidenceKey(challenger),
                    "defeated_by_stronger_evidence"
            );
        }
    }
*/



/*    public Citation selectWinningChallenger(
            ClaimEvidence dominant,
            List<Citation> candidates
    ) {
        return candidates.stream()
                // 只看 oppose
                .filter(c -> c.polarity() != dominant.polarity())
                // 计算对抗得分
                .max(Comparator.comparingDouble(c -> {
                    double conflictIntensity =
                            Math.abs(c.confidence() - dominant.confidence());

                    Duration since =
                            Duration.between(c.updatedAt(), Instant.now());

                    double freshnessBonus =
                            1.0 / (1.0 + since.toMinutes());

                    return c.confidence()
                            * conflictIntensity
                            * freshnessBonus;
                }))
                .orElseThrow(() ->
                        new IllegalStateException("No valid challenger found"));
    }*/
}