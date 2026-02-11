package com.yef.agent.repository;

import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.EpistemicStatus;

public interface StatusTransitionRepository {

    void writeStatusTransition(String userId,
                               String claimKey,
                               EpistemicStatus from,
                               EpistemicStatus to,
                               ClaimDelta delta);



}
