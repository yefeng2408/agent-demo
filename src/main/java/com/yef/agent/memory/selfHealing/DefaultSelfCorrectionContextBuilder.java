/*
package com.yef.agent.memory.selfHealing;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.BeliefStore;
import com.yef.agent.memory.event.EpistemicEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class DefaultSelfCorrectionContextBuilder implements SelfCorrectionContextBuilder {

    @Autowired
    private BeliefStore beliefStore;

    @Override
    public SelfCorrectionContext from(EpistemicEvent event) {
        // 1. 定位新 claim
        ClaimEvidence newClaim = resolveNewClaim(event);
        // 2. 当前 claim 冲突了哪些已有 claim？
        List<ClaimEvidence> conflicted = findConflictedClaims(event.userId(), newClaim);
        // 3. 组装 Context
        return new SelfCorrectionContext(
                event.userId(),
                newClaim,
                conflicted,
                event.type(),
                event.reason(),
                event.at()
        );
    }


    private ClaimEvidence resolveNewClaim(EpistemicEvent event) {

        String key = event.triggerKey();

        ClaimEvidence claim = beliefStore.getByKey(key);

        if (claim == null) {
            throw new IllegalStateException(
                    "SelfCorrection failed: trigger claim not found: " + key
            );
        }

        return claim;
    }

    private List<ClaimEvidence> findConflictedClaims(
            String userId,
            ClaimEvidence newClaim
    ) {
        return beliefStore.findByPattern(
                userId,
                newClaim.subjectId(),
                newClaim.predicate(),
                newClaim.objectId(),
                newClaim.quantifier(),
                !newClaim.polarity()   // polarity 翻转
        );
    }

}
*/
