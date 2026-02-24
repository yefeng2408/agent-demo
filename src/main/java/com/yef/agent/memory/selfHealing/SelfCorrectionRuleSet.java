package com.yef.agent.memory.selfHealing;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.selfHealing.eum.ClaimRelationType;
import com.yef.agent.memory.selfHealing.mutation.ConfidenceAdjust;
import com.yef.agent.memory.selfHealing.mutation.RelationAttach;
import com.yef.agent.memory.selfHealing.mutation.StatusOverride;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;

/**
 * 自我修复规则集合
 *
 * Current version only supports explicit user self-correction rules.
 * Implicit or inferred corrections are intentionally excluded
 * to avoid memory pollution.
 *
 * TODO: R3 & R4
 * 类型         示例
 * 时间修正     “以前有，现在没有了”
 * 范围修正     “不是所有情况”
 * 语气软化     “我不太确定”
 *
 * TODO: R5 & R6
 * 类型         示例
 * 间接暗示     “好像不是这样”
 * 情绪表达     “我觉得有点不对”
 * 反问句       “我什么时候说过这个？”
 */
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
        return e.source() != null && "USER_STATEMENT".equalsIgnoreCase(e.source().name());
    }

    private String key(ClaimEvidence e) {
        return keyCodec.buildEvidenceKey(e);
    }
}