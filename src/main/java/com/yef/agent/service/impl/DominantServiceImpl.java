package com.yef.agent.service.impl;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.pipeline.EpistemicContext;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.repository.ClaimEvidenceRepository;
import com.yef.agent.service.DominantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class DominantServiceImpl implements DominantService {

    private static final double CHALLENGE_THRESHOLD = 0.15;

    @Autowired
    private ClaimEvidenceRepository claimEvidenceRepository;
    @Autowired
    private KeyCodec keyCodec;

/*    @Override
    public boolean isRealChallenge(
            ClaimEvidence dominant,
            ClaimEvidence challenger,
            ClaimDelta delta
    ) {
        // 1️⃣ 变化幅度
        double deltaMagnitude =
                Math.abs(delta.afterConfidence() - delta.beforeConfidence());

        // 2️⃣ 新鲜度（时间越久，挑战越“像新信息”）
        Duration sinceLastChange =
                Duration.between(dominant.lastStatusChangedAt(), Instant.now());

        double noveltyFactor =
                1.0 - Math.exp(-sinceLastChange.toMinutes() / 10.0);

        double challengeForce =
                challenger.confidence()
                        * deltaMagnitude
                        * noveltyFactor;

        // 3️⃣ 稳定阻力
        Duration age =
                Duration.between(dominant.lastStatusChangedAt(), Instant.now());

        double ageFactor = Math.log1p(age.toMinutes());
        double inertia = 0.6;

        double resistanceForce =
                dominant.confidence()
                        * ageFactor
                        * inertia;

        return challengeForce > resistanceForce;
    }*/

/*    @Override
    public boolean challengerWins(ClaimEvidence dominant, ClaimEvidence challenger) {

        double dominantEffective =
                dominant.supportConfidenceAt() * timeDecayFactor(dominant.dominantSince());

        return challenger.confidence() > dominantEffective;
    }*/

/*
    private double timeDecayFactor(Instant since) {
        long minutes = Duration.between(since, Instant.now()).toMinutes();
        return 1.0 / (1.0 + 0.01 * minutes);
    }
*/

/*
    @Override
    public void switchDominant(
            String userId,
            String claimKey,
            ClaimEvidence oldDominant,
            ClaimEvidence newDominant,
            String reason) {

        String oldDominantKey = keyCodec.buildEvidenceKey(oldDominant);
        String newDominantKey = keyCodec.buildEvidenceKey(newDominant);
        claimEvidenceRepository.writeOverriddenBy(
                oldDominantKey,
                newDominantKey,
                reason
        );

        claimEvidenceRepository.clearDominant(userId, claimKey);

        claimEvidenceRepository.writeDominant(
                userId,
                claimKey,
                newDominantKey,
                newDominant.confidence(),
                reason
        );
    }
*/


    @Override
    public Optional<DominantClaimVO> loadDominantView(String userId, String claimKey) {

        Optional<ClaimEvidence> domOpt =
                claimEvidenceRepository.loadDominant(userId, claimKey);

        if (domOpt.isEmpty()) {
            return Optional.empty();
        }

        ClaimEvidence dom = domOpt.get();

        Instant since = dom.dominantSince();
        Duration age = Duration.between(since, Instant.now());

        double decay =
                Math.exp(-age.toHours() / 72.0); // 3 天半衰

        double effective = dom.supportConfidenceAt() * decay;

        boolean recentlyChallenged =
                dom.lastStatusChangedAt() != null &&
                        Duration.between(dom.lastStatusChangedAt(), Instant.now())
                                .toMinutes() < 30;

        return Optional.of(
                new DominantClaimVO(
                        claimKey,
                        dom,
                        dom.confidence(),
                        effective,
                        since,
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