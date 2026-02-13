package com.yef.agent.memory.narrative.tone.plpeline;

import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.NarrativeTone;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.narrative.tone.rule.NarrativeToneRule;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;
import java.util.Optional;

public final class NarrativeTonePipeline {

    private final List<NarrativeToneRule> rules;
    private final NarrativeTone fallback;

    public NarrativeTonePipeline(List<NarrativeToneRule> rules, NarrativeTone fallback) {
        this.rules = List.copyOf(rules);
        this.fallback = fallback;
    }

    public NarrativeTone pick(DominantClaimVO dom,
                              NarrativeContext ctx,
                              DominantNarrativeScore score,
                              List<NarrativeCue> cues) {

        return rules.stream()
                .sorted((a,b) -> Integer.compare(b.priority(), a.priority()))
                .map(r -> r.decide(dom, ctx, score, cues))
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(fallback);
    }
}
