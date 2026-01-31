package com.yef.agent.memory.explain.biz;

import com.yef.agent.memory.explain.ExplanationItem;
import com.yef.agent.memory.selector.DecisionReason;
import java.util.List;

public interface EpistemicExplanationRepository {


    /**
     * 根据裁决原因，生成一组解释说明
     */
    List<ExplanationItem> explainAll(DecisionReason reason);
}