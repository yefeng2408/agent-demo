package com.yef.agent.memory.narrative;

import com.yef.agent.graph.answer.Citation;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import java.util.List;
import java.util.Objects;

/*
public record NarrativeDecision(
        String claimKey,

        // 1) 基础裁决
        NarrativeCondition condition,
        NarrativeTone tone,

        // 2) 可解释输入快照（便于 debug / 观察）
        DominantNarrativeScore score,
        int overrideCount,
        boolean recentlyChallenged,
        Duration dominanceAge,

        // 3) 生成策略（驱动 Builder）
        boolean includeCitations,          // 是否输出证据引用
        boolean includeUncertaintyCue,      // 是否加“不确定/可能”提示
        boolean includeContestedCue,        // 是否加“有争议/近期被挑战”提示
        boolean includeOwnershipCue,        // 是否加“据我所知/你的陈述显示”这类 ownership
        boolean includeRecentlyChangedCue,
        String reasonCode                  // 一句话标识本次裁决原因（日志&单测很香）
) {}

*/

public record NarrativeDecision(
        NarrativeTone tone,
        NarrativeCondition condition,
        DominantNarrativeScore score,
        List<NarrativeCue> cues,
        List<Citation> citations
) {
    public String render(String core) {
        Objects.requireNonNull(core);
        // 1️⃣ 创建 RenderContext（渲染快照）
        NarrativeRenderContext ctx =
                new NarrativeRenderContext(
                        tone,
                        cues,
                        null,        // claim 可后续补
                        core,
                        List.of()
                );

        // 2️⃣ Tone 渲染核心文本
        String tonedCore = NarrativeToneRenderer.render(tone, core);
        ctx = ctx.withCore(tonedCore);
        // 3️⃣ 让 Cue 自己 apply（🔥 关键变化）
        for (NarrativeCue cue : cues) {
            cue.apply(ctx);
        }
        // 4️⃣ 从 ctx 取最终字符串
        return ctx.build();
    }
}