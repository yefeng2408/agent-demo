package com.yef.agent.memory.explain.biz;

import com.yef.agent.memory.explain.StatusTransitionSnapshot;
import java.util.Optional;

public interface StatusTransitionExplanationRepository {

    /**
     * 返回“与当前裁决最相关的一次状态迁移”
     */
    Optional<StatusTransitionSnapshot> findLatestRelevant(String userId);


}
