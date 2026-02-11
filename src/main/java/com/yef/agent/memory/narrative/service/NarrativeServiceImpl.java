package com.yef.agent.memory.narrative.service;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.decision.DecisionReason;
import com.yef.agent.memory.explain.Audience;
import com.yef.agent.memory.narrative.*;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.memory.vo.OverriddenEdgeVO;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class NarrativeServiceImpl implements NarrativeService {

    // ========= 1️⃣ 构建叙事评分 =========
    @Override
    public DominantNarrativeScore buildScore(DominantClaimVO dom) {

        double confidence = dom.effectiveConfidence();
        long ageMillis = System.currentTimeMillis() - dom.dominantSince().toEpochMilli();

        // 简单年龄换算（分钟）
        long ageMinutes = ageMillis / 60000;

        boolean recentlyChallenged =
                dom.overrideCount() > 0 && ageMinutes < 30;

        return new DominantNarrativeScore(
                confidence,
                ageMinutes,
                dom.status(),
                recentlyChallenged
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
        if (ctx.getDominant() == null) {
            return NarrativeCondition.TENTATIVE;
        }
        // 1. 刚被挑战 / 刚胜出
        if (ctx.isRecentlyChallenged()) {
            return NarrativeCondition.RECENTLY_CHANGED;
        }
        // 2. 有 override 历史（但不是最近）
        if (!ctx.getOverrideHistory().isEmpty()) {
            return NarrativeCondition.CONTESTED;
        }
        // 3. dominant 已稳定存在一段时间
        if (ctx.getDominantDuration().compareTo(STABLE_DURATION) >= 0) {
            return NarrativeCondition.CERTAIN;
        }
        // 4. 默认：短期 dominant
        return NarrativeCondition.TENTATIVE;
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

        DominantClaimVO d = ctx.getDominant();

        if (d == null) {
            return unsure("目前没有足够信息判断。");
        }

        if (!ctx.getOverrideHistory().isEmpty()) {
            return cautious(
                    "目前来看你拥有" + d.objectName() +
                            "，这是基于你最近的修正判断得出的。"
            );
        }

        if (ctx.getDominantDuration().toHours() < 6) {
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