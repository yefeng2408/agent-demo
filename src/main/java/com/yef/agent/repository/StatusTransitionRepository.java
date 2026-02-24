package com.yef.agent.repository;

import com.yef.agent.graph.answer.BeliefState;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.DeltaDirection;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.pipeline.TransitionReason;
import com.yef.agent.memory.vo.OverrideEdge;

import java.util.List;
import java.util.Optional;

public interface StatusTransitionRepository {

    boolean existsOverrideTransition(String userId,
                                     String fromClaimKey,
                                     String toClaimKey);


    boolean existsOverride(String userId,
                           String slotKey,
                           String fromClaimKey,
                           String toClaimKey);



    List<OverrideEdge> loadOverrideChain(String userId, String slotKey, int limit);

    void writeOverrideTransition(String userId,
                                 String slotKey,
                                 String fromClaimKey,
                                 String toClaimKey,
                                 double intentConfidence,
                                 ClaimDelta delta);


    void writeStatusTransition(String userId,
                               String claimKey,
                               EpistemicStatus from,
                               EpistemicStatus to,
                               ClaimDelta delta,
                               TransitionReason reason
                               );


    void arbitrateDominant(String slotKey, String claimKey, String beliefId, String bootstrap);

    Optional<BeliefState> loadBeliefState(String slotKey);


    double updateMomentum(double current, DeltaDirection direction);

    void updateBeliefMomentum(String slotKey, double momentum);

}
