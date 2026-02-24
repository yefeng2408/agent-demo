package com.yef.agent.service;

import com.yef.agent.memory.pipeline.TransitionReason;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.Optional;


public interface DominantService {

/*
    boolean isRealChallenge(
            ClaimEvidence dominant,
            ClaimEvidence challenger,
            ClaimDelta delta
    );


    boolean challengerWins(
            ClaimEvidence dominant,
            ClaimEvidence challenger
    );

    void switchDominant(
            String userId,
            String claimKey,
            ClaimEvidence oldDominant,
            ClaimEvidence newDominant,
            String reason
    );
*/

    void recomputeDominant(String userId, String claimKey);

    Optional<DominantClaimVO> loadDominantView(String userId, String soltKey);


    //void recomputeAfterDeltas(EpistemicContext ctx, List<ClaimDelta> deltas);
}
