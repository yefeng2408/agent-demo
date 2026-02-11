package com.yef.agent.service;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.pipeline.EpistemicContext;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;
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

    Optional<DominantClaimVO> loadDominantView(String userId, String claimKey);


    //void recomputeAfterDeltas(EpistemicContext ctx, List<ClaimDelta> deltas);
}
