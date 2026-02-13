package com.yef.agent.memory.narrative.service;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.decision.DecisionReason;
import com.yef.agent.memory.explain.Audience;
import com.yef.agent.memory.narrative.*;
import com.yef.agent.memory.narrative.DefaultNarrativeDecisionEngine;
import com.yef.agent.memory.narrative.NarrativeDecision;
import com.yef.agent.memory.narrative.repository.NarrativeRepository;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.memory.vo.OverriddenEdgeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class NarrativeServiceImpl implements NarrativeService {

    private final DefaultNarrativeDecisionEngine decisionEngine = new DefaultNarrativeDecisionEngine();

    @Autowired
    NarrativeRepository narrativeRepository;

    // ========= 1️⃣ 构建叙事评分 =========
    @Override
    public DominantNarrativeScore buildScore(DominantClaimVO dom) {

        double confidence = dom.effectiveConfidence();
        long ageMillis = System.currentTimeMillis() - dom.dominantSince().toEpochMilli();
        Duration dominanceAge = Duration.ofMillis(ageMillis);

        return new DominantNarrativeScore(
                confidence,
                dom.status(),
                dominanceAge,
                dom.recentlyChallenged()
        );
    }

    // ========= 2️⃣ Tone 判定 =========
    @Override
    public NarrativeTone decideTone(DominantNarrativeScore s) {

        if (s.status() == EpistemicStatus.DENIED) {
            return NarrativeTone.UNCERTAIN;
        }
        if (s.effectiveConfidence() > 0.9 && !s.recentlyChallenged()) {
            return NarrativeTone.ASSERTIVE;
        }
        if (s.effectiveConfidence() > 0.75) {
            return NarrativeTone.CONFIDENT;
        }
        if (s.effectiveConfidence() > 0.55) {
            return NarrativeTone.CAUTIOUS;
        }
        return NarrativeTone.UNCERTAIN;
    }

    // ========= 3️⃣ 语气渲染 =========
    @Override
    public String renderWithTone(NarrativeTone tone, String core) {
        return switch (tone) {
            case ASSERTIVE -> "可以明确地说，" + core;
            case CONFIDENT -> "根据目前信息，" + core;
            case CAUTIOUS -> "看起来可能是这样：" + core;
            case UNCERTAIN -> "目前还无法确定，但" + core;
        };
    }


    private static final Duration STABLE_DURATION = Duration.ofDays(7);
    private static final Duration RECENT_WINDOW = Duration.ofHours(24);

    @Override
    public NarrativeCondition decideOwnershipTone(NarrativeContext ctx) {
        if (ctx.dominant() == null) {
            return NarrativeCondition.TENTATIVE;
        }
        // 1. 刚被挑战 / 刚胜出
        if (ctx.recentlyChallenged()) {
            return NarrativeCondition.RECENTLY_CHANGED;
        }
        // 2. 有 override 历史（但不是最近）
        if (!ctx.overrideHistory().isEmpty()) {
            return NarrativeCondition.CONTESTED;
        }
        // 3. dominant 已稳定存在一段时间
        if (ctx.dominantDuration().compareTo(STABLE_DURATION) >= 0) {
            return NarrativeCondition.CERTAIN;
        }
        // 4. 默认：短期 dominant
        return NarrativeCondition.TENTATIVE;
    }


    public NarrativeContext buildNarrativeContext(String userId, String claimKey) {
        Optional<DominantClaimVO> domOpt = narrativeRepository.loadDominantNarrativeView(userId, claimKey);
        if (domOpt.isEmpty()) {
            return new NarrativeContext(null, List.of(), Duration.ZERO, false);
        }

        DominantClaimVO dom = domOpt.get();
        List<OverrideEvent> history = narrativeRepository.loadOverrideTimeline(dom.claimKey());
        Duration duration = Duration.between(dom.dominantSince(), Instant.now());

        boolean recentlyChallenged =
                history.stream().anyMatch(e ->
                        Duration.between(e.at(), Instant.now()).toHours() < 24);

        return new NarrativeContext(dom, history, duration, recentlyChallenged);
    }


    //  把 ctx + score 收口成 decision
    @Override
    public NarrativeDecision decide(String userId, DominantClaimVO dom) {
        String claimKey = dom.claimKey();

        // 1) score（更偏“数值快照”）
        DominantNarrativeScore score = this.buildScore(dom);

        // 2) ctx（更偏“事实上下文”：overrideHistory、dominantDuration...）
        NarrativeContext ctx = this.buildNarrativeContext(userId, claimKey);

        // 3) decision（真正驱动 builder）
        return decisionEngine.decide(dom, ctx, score);
    }


    @Override
    public String renderOwnershipAnswer(NarrativeTone tone, ClaimEvidence dominant) {
        return "";
    }


    public Narrative buildNarrative(
            DominantClaimVO dominant,
            List<OverriddenEdgeVO> history,
            DecisionReason reason,
            Audience audience
    ) {
        NarrativeFrame frame = resolveFrame(dominant, history);

        return switch (frame) {
            case STABLE_BELIEF -> stableNarrative(dominant, audience);

            case REINFORCED_BELIEF -> reinforcedNarrative(dominant, history, audience);

            case CHANGED_MIND -> changedMindNarrative(dominant, history.get(0), reason, audience);

            default -> uncertainNarrative(dominant, audience);
        };
    }

    public NarrativeFrame resolveFrame(
            DominantClaimVO dominant,
            List<OverriddenEdgeVO> history
    ) {
        if (history.isEmpty()) {
            return NarrativeFrame.STABLE_BELIEF;
        }

        if (history.size() == 1) {
            return NarrativeFrame.CHANGED_MIND;
        }

        return NarrativeFrame.REINFORCED_BELIEF;
    }


    private Narrative changedMindNarrative(
            DominantClaimVO current,
            OverriddenEdgeVO edge,
            DecisionReason reason,
            Audience audience
    ) {
        return Narrative.builder()
                .lead("我之前的判断发生了改变。")
                .context(
                        "原先我倾向于认为：%s。"
                                .formatted(edge.fromSummary())
                )
                .shift(
                        "后来出现了新的信息：%s，"
                                + "它在可信度和时效性上都更有优势。"
                                .formatted(edge.toSummary())
                )
                .decision(
                        "因此，我现在更倾向于认为：%s。"
                                .formatted(current.summary())
                )
                .confidenceTone(toneFromConfidence(current.confidence()))
                .build();
    }

    public NarrativeAnswer ownershipNarrative(NarrativeContext ctx) {

        DominantClaimVO d = ctx.dominant();

        if (d == null) {
            return unsure("目前没有足够信息判断。");
        }

        if (!ctx.overrideHistory().isEmpty()) {
            return cautious(
                    "目前来看你拥有" + d.objectName() +
                            "，这是基于你最近的修正判断得出的。"
            );
        }

        if (ctx.dominantDuration().toHours() < 6) {
            return cautious(
                    "现在我认为你拥有" + d.objectName() +
                            "，但这个结论还比较新。"
            );
        }

        return confident(
                "你确实拥有" + d.objectName() +
                        "。"
        );
    }

}