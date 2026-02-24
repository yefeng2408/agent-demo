package com.yef.agent.memory.narrative.citation.pipeline;

import com.yef.agent.graph.answer.Citation;
import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.narrative.citation.CitationStrategy;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;



public final class CitationPipeline {

    private final List<CitationStrategy> strategies;

    public CitationPipeline(List<CitationStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    // v6.1 Autonomous Narrative Brain: Citation 决策由策略链驱动，Engine 仅编排
    public List<Citation> build(DominantClaimVO dom,
                                NarrativeContext ctx,
                                DominantNarrativeScore score,
                                List<NarrativeCue> cues) {

        // v6.1 设计：
        // 1️⃣ 不再简单取第一个策略
        // 2️⃣ 允许策略链“短路”或“合并结果”
        // 3️⃣ Engine 不理解 citation 逻辑，只负责 orchestration

        List<Citation> result = List.of();

        for (CitationStrategy strategy : strategies.stream()
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .toList()) {

            List<Citation> out = strategy.build(dom, ctx, score, cues);

            // 约定：
            // - 返回 null 表示“跳过我，让下一个策略处理”
            // - 返回 emptyList() 表示“明确不引用（短路）”
            // - 返回非空表示“我决定了引用内容（短路）”

            if (out == null) {
                continue;
            }

            result = out;
            break;
        }

        return result;
    }
}