package com.yef.agent.memory.narrative;

import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import java.util.List;

/**
 * Rendering Snapshot（渲染快照）
 *
 * DecisionContext（NarrativeContext）
 *         ↓
 * DecisionEngine
 *         ↓
 * NarrativeDecision（战略对象）
 *         ↓
 * RenderContext（渲染快照）
 */
public class NarrativeRenderContext {

    private NarrativeTone tone;
    private List<NarrativeCue> cues;

    private String core;

    private final StringBuilder prefix = new StringBuilder();
    private final StringBuilder suffix = new StringBuilder();

    private List<Citation> citations;

    public NarrativeRenderContext(
            NarrativeTone tone,
            List<NarrativeCue> cues,
            ClaimEvidence claim,
            String core,
            List<Citation> citations
    ) {
        this.tone = tone;
        this.cues = cues;
        this.core = core;
        this.citations = citations;
    }

    // ====== 给 Cue 用的接口 ======

    public void appendPrefix(String s) {
        prefix.append(s);
    }

    public void appendSuffix(String s) {
        suffix.append(s);
    }

    public void setCore(String core) {
        this.core = core;
    }

    public String build() {
        return prefix + core + suffix;
    }

    public NarrativeRenderContext withCore(String core) {
        this.core = core;
        return this;
    }
}