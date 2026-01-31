package com.yef.agent.memory.selfHealing;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class DefaultSelfCorrectionResolver implements SelfCorrectionResolver {

    private final SelfCorrectionRuleSet ruleSet;
    private final KeyCodec keyCodec;

    public DefaultSelfCorrectionResolver(
            SelfCorrectionRuleSet ruleSet,
            KeyCodec keyCodec) {

        this.ruleSet = ruleSet;
        this.keyCodec = keyCodec;
    }

    @Override
    public SelfCorrectionResult resolve(
            ClaimEvidence newClaim,
            List<ClaimEvidence> existingClaims) {

        if (existingClaims == null || existingClaims.isEmpty()) {
            return SelfCorrectionResult.noop();
        }

        List<SelfCorrectionRule> rules =
                ruleSet.rules().stream()
                        .sorted(Comparator.comparingInt(SelfCorrectionRule::priority).reversed())
                        .toList();

        for (ClaimEvidence old : existingClaims) {
            for (SelfCorrectionRule rule : rules) {
                if (rule.when().test(newClaim, old)) {

                    List<ClaimMutation> mutations = rule.then().apply(newClaim, old);

                    DominantClaimHint hint = new DominantClaimHint(
                            keyCodec.buildEvidenceKey(newClaim),
                            "USER_SELF_CORRECTION:" + rule.ruleId() );

                    return new SelfCorrectionResult(
                            true,
                            mutations,
                            Optional.of(hint) );
                }
            }
        }

        return SelfCorrectionResult.noop();
    }
}