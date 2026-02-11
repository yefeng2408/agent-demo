package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.memory.DeltaDirection;
import com.yef.agent.memory.pipeline.EpistemicContext;
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

    //短时间内用户前后声明不一致。防止防震荡时间，可以调该数值。
    private static final Duration STATUS_COOLDOWN = Duration.ofSeconds(30);

    private final ClaimEvidenceRepositoryImpl claimEvidenceRepository;
    private final StatusTransitionRepositoryImpl statusTransitionRepository;
    private final DominantService dominantService;
    private final KeyCodec keyCodec;

    public StatusTransitionStage(ClaimEvidenceRepositoryImpl claimEvidenceRepository,
                                 StatusTransitionRepositoryImpl statusTransitionRepository,
                                 DominantService dominantService,
                                 KeyCodec keyCodec) {

        this.claimEvidenceRepository = claimEvidenceRepository;
        this.statusTransitionRepository = statusTransitionRepository;
        this.dominantService = dominantService;
        this.keyCodec = keyCodec;
    }

    public void apply(EpistemicContext ctx, List<ClaimDelta> deltas) {
        String userId = ctx.userId();
        for (ClaimDelta delta : deltas) {
            if (delta.kind() == ClaimDelta.DeltaKind.CONFIDENCE_ONLY) {
                // ① 只做一件事：写置信度
                claimEvidenceRepository.updateConfidence(
                        delta.claimKey(),
                        delta.afterConfidence()
                );
            }
        }
        // ② 再统一做「状态迁移判断」
        for (ClaimDelta delta : deltas) {
            handleStatusTransition(userId, delta);
        }
        // ③ 统一收口（dominant / override）
        afterAllDeltasApplied(ctx.userId(), deltas);
    }


    /**
     * v4 FINAL VERSION
     *
     * 作用：
     * 1️⃣ 按 claimSlot 聚合 delta
     * 2️⃣ 判断是否真实 challenge
     * 3️⃣ 重算 dominant
     * 4️⃣ 写 DOMINANT / OVERRIDDEN_BY 图路径
     */
    private void afterAllDeltasApplied(String userId, List<ClaimDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        // ========= 1️⃣ 按 slot 分组 =========
        Map<String, List<ClaimDelta>> slotMap =
                deltas.stream()
                        .collect(Collectors.groupingBy(d -> slotKeyOf(d.claimKey())));

        for (Map.Entry<String, List<ClaimDelta>> entry : slotMap.entrySet()) {

            List<ClaimDelta> slotDeltas = entry.getValue();

            // ========= 2️⃣ 是否真实挑战 =========
            if (!isRealChallenge(slotDeltas)) {
                continue;
            }

            // ========= 3️⃣ 找 challenger（UP 最大者） =========
            ClaimDelta challengerDelta = pickChallenger(slotDeltas);
            if (challengerDelta == null) {
                continue;
            }

            String challengerKey = challengerDelta.claimKey();

            // ========= 4️⃣ 查询当前 slot 所有 evidence =========
            List<ClaimEvidence> all =
                    claimEvidenceRepository.loadAllByClaimKey(userId, challengerKey);

            if (all == null || all.size() < 2) {
                continue;
            }

            // ========= 5️⃣ 重新计算 dominant =========
            ClaimEvidence newDominant = all.stream()
                    .max(Comparator.comparingDouble(ClaimEvidence::confidence))
                    .orElse(null);

            if (newDominant == null) {
                continue;
            }

            String claimSlotKey = slotKeyOf(challengerKey);

            // ========= 6️⃣ 写 Neo4j DOMINANT =========
            // 清理旧 dominant
            claimEvidenceRepository.clearDominant(userId, claimSlotKey);

            // 写新的 dominant
            claimEvidenceRepository.writeDominant(
                    userId,
                    claimSlotKey,
                    keyCodec.buildEvidenceKey(newDominant),
                    newDominant.confidence(),
                    "REAL_CHALLENGE"
            );

            // ========= 7️⃣ 写 OVERRIDDEN_BY 路径 =========
            ClaimDelta dominated = pickDominated(slotDeltas);

            if (dominated != null) {
                claimEvidenceRepository.writeOverriddenBy(
                        dominated.claimKey(),
                        challengerKey,
                        "REAL_CHALLENGE"
                );
            }
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


    private void handleStatusTransition(String userId, ClaimDelta delta) {
        //查询claim最新的置信度
        ClaimEvidence evidence = claimEvidenceRepository.loadByKey(userId, delta.claimKey());

        EpistemicStatus before = evidence.epistemicStatus();
        EpistemicStatus after = decideWithHysteresis(before, evidence.confidence());

        boolean strongDelta = Math.abs(delta.afterConfidence() - delta.beforeConfidence()) >= 0.35;
        if (inCooldown(evidence) && !strongDelta) {
            return;
        }

        if (before != after) {
            // ① 写 claim.epistemicStatus
            claimEvidenceRepository.updateEpistemicStatus(
                    delta.claimKey(),
                    after
            );

            // ② 写 StatusTransition 记录（可审计）
            statusTransitionRepository.writeStatusTransition(
                    userId,
                    delta.claimKey(),
                    before,
                    after,
                    delta
            );
        }

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