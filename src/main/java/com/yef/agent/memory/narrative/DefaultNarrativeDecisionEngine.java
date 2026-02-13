package com.yef.agent.memory.narrative;

import com.yef.agent.graph.answer.Citation;
import com.yef.agent.memory.narrative.citation.pipeline.CitationPipeline;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.narrative.cue.pipeline.NarrativeCuePipeline;
import com.yef.agent.memory.narrative.tone.plpeline.NarrativeTonePipeline;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;

/**
 * 只负责产出 Decision
 */
public final class DefaultNarrativeDecisionEngine {

    private final NarrativeCuePipeline cuePipeline;
    private final NarrativeTonePipeline tonePipeline;
    private final CitationPipeline citationPipeline;

    public DefaultNarrativeDecisionEngine(NarrativeCuePipeline cuePipeline, NarrativeTonePipeline tonePipeline, CitationPipeline citationPipeline) {
        this.cuePipeline = cuePipeline;
        this.tonePipeline = tonePipeline;
        this.citationPipeline = citationPipeline;
    }

    public NarrativeDecision decide(
            DominantClaimVO dom,
            NarrativeContext ctx,
            DominantNarrativeScore score
    ) {
        // ① cue 收集
        List<NarrativeCue> cues = cuePipeline.collect(dom, ctx, score);
        // ② tone 选择
        NarrativeTone tone = tonePipeline.pick(dom, ctx, score, cues);
        // ③ citation 构建
        List<Citation> citations = citationPipeline.build(dom, ctx, score, cues);
        // ④ condition 解析（Meta Label）
        NarrativeCondition condition = resolveCondition(ctx, score);

        return new NarrativeDecision(
                tone,
                condition,
                score,
                cues,
                citations   // ⭐ 必须加入
        );
    }

    private NarrativeCondition resolveCondition(NarrativeContext ctx, DominantNarrativeScore score) {
        // 示例：你可按自己的阈值替换
        if (ctx.recentlyChallenged()) return NarrativeCondition.RECENTLY_CHANGED;
        if (!ctx.overrideHistory().isEmpty()) return NarrativeCondition.CONTESTED;
        if (score.effectiveConfidence() < 0.65) return NarrativeCondition.TENTATIVE;
        return NarrativeCondition.CERTAIN;
    }

    private NarrativeTone decideTone(NarrativeCondition condition, DominantNarrativeScore score) {
        return switch (condition) {
            case CERTAIN -> score.effectiveConfidence() > 0.9 ? NarrativeTone.ASSERTIVE : NarrativeTone.CONFIDENT;
            case TENTATIVE, CONTESTED, RECENTLY_CHANGED -> NarrativeTone.CAUTIOUS;
        };
    }


}