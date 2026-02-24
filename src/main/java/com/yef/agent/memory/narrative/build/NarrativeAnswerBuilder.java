package com.yef.agent.memory.narrative.build;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.memory.narrative.NarrativeDecision;
import com.yef.agent.memory.narrative.renderer.FactRenderer;
import com.yef.agent.memory.narrative.service.NarrativeService;
import com.yef.agent.memory.vo.DominantClaimVO;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public final class NarrativeAnswerBuilder {

    private final NarrativeService narrativeService;
    private final FactRenderer factRenderer;

    public NarrativeAnswerBuilder(NarrativeService narrativeService, FactRenderer factRenderer) {
        this.narrativeService = narrativeService;
        this.factRenderer = factRenderer;
    }

/*
    public AnswerResult build(
            String userId,
            ExtractedRelation relation,
            DominantClaimVO dom
    ) {

        // ① 渲染事实核心
        String core = factRenderer.render(dom.claim());

        // ② 决策（真正的大脑）
        NarrativeDecision decision = narrativeService.decide(userId, dom);

        // ③ 决策自己负责渲染（Zero-if Builder）
        String finalAnswer = decision.render(core);

        // ④ 决策自己决定 citation
        List<Citation> citations = decision.citations(Citation::from, dom);

        return AnswerResult.pk(
                finalAnswer,
                citations,
                decision.tone(),
                decision.score()
        );
    }
*/

}