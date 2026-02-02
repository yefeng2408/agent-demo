package com.yef.agent.memory.selfHealing;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;

@Component
public class SelfCorrectionRuleSet {

    private final KeyCodec keyCodec;

    public SelfCorrectionRuleSet(KeyCodec keyCodec) {
        this.keyCodec = keyCodec;
    }

    public List<SelfCorrectionRule> rules() {
        return List.of(
                strongUserSelfCorrection(),
                mediumUserSelfCorrection()
        );
    }

    /** R1：用户明确自我推翻一个 CONFIRMED 的旧声明 */
    private SelfCorrectionRule strongUserSelfCorrection() {
        return new SelfCorrectionRule(
                "R1_STRONG_USER_SELF_CORRECTION",
                100,
                (n, o) -> sameSlot(n, o)
                        && n.polarity() != o.polarity()
                        && isUser(n)
                        && o.epistemicStatus() == EpistemicStatus.CONFIRMED,
                (n, o) -> List.of(
                        new ConfidenceAdjust(key(o), -0.30),
                        new StatusOverride(key(o), EpistemicStatus.DENIED),
                        //new RelationAttach(key(o), key(n), "OVERRIDDEN_BY")
                        new RelationAttach(key(o), key(n), ClaimRelationType.SUPERSEDES)
                )
        );
    }

    /** R2：用户反驳一个 WEAKLY_SUPPORTED 的旧声明 */
    private SelfCorrectionRule mediumUserSelfCorrection() {
        return new SelfCorrectionRule(
                "R2_MEDIUM_USER_SELF_CORRECTION",
                80,

                // condition
                (ClaimEvidence n, ClaimEvidence o) ->
                        sameSlot(n, o)
                                && n.polarity() != o.polarity()
                                && isUser(n)
                                && o.epistemicStatus() == EpistemicStatus.HYPOTHETICAL,

                // mutations
                (ClaimEvidence n, ClaimEvidence o) -> List.of(
                        new ConfidenceAdjust(
                                key(o),
                                -0.15
                        ),
                        new RelationAttach(
                                key(o),
                                key(n),
                                ClaimRelationType.OPPOSES
                        )
                )
        );
    }

    // ---------- helpers ----------

    private boolean sameSlot(ClaimEvidence a, ClaimEvidence b) {
        return Objects.equals(a.subjectId(), b.subjectId())
                && Objects.equals(a.predicate(), b.predicate())
                && Objects.equals(a.objectId(), b.objectId())
                && Objects.equals(a.quantifier(), b.quantifier());
    }

    private boolean isUser(ClaimEvidence e) {
        return e.source() != null
                && "USER_STATEMENT".equalsIgnoreCase(e.source().name());
    }

    private String key(ClaimEvidence e) {
        return keyCodec.buildEvidenceKey(e);
    }
}