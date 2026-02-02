package com.yef.agent.memory.selfHealing.builder;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.selfHealing.SelfCorrectionContext;
import com.yef.agent.memory.selfHealing.repository.ClaimRepository;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

@Component
public class DefaultSelfCorrectionContextBuilder implements SelfCorrectionContextBuilder {

   private final ClaimRepository claimRepository;
   private final KeyCodec keyCodec;


    public DefaultSelfCorrectionContextBuilder(ClaimRepository claimRepository, KeyCodec keyCodec) {
        this.claimRepository = claimRepository;
        this.keyCodec = keyCodec;
    }

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
        KeyCodec.DecodedKey key = keyCodec.decode(event.triggerKey());
        Optional<ClaimEvidence> byKey = claimRepository.findByKey(key);

        if (byKey == null) {
            throw new IllegalStateException(
                    "SelfCorrection failed: trigger claim not found: " + key
            );
        }
        return byKey.get();
    }

    private List<ClaimEvidence> findConflictedClaims(
            String userId,
            ClaimEvidence newClaim) {
        return claimRepository.findBySlot(
                userId,
                newClaim.subjectId(),
                newClaim.predicate().name(),
                newClaim.objectId(),
                newClaim.quantifier(),
                !newClaim.polarity()   // polarity 翻转
        );
    }

}
