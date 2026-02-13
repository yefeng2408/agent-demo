package com.yef.agent.memory.narrative.cue.strategy;

import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.cue.NarrativeCue;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.Optional;


public interface NarrativeCueStrategy {

    Optional<NarrativeCue> buildCue(
            DominantClaimVO dom,
            NarrativeContext ctx,
            DominantNarrativeScore score
    );

    int order(); // 控制输出顺序（prefix 渲染顺序）

}

