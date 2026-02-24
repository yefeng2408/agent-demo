package com.yef.agent.memory.decision.biz;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.decision.ConfirmedDecision;
import com.yef.agent.memory.decision.DecisionReason;
import com.yef.agent.memory.decision.DeniedDecision;
import com.yef.agent.memory.decision.UncertainDecision;
import java.util.List;
import java.util.Optional;

/**
 * 裁决结果顶层接口
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true   // ⭐⭐⭐⭐ 必须
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConfirmedDecision.class, name = "CONFIRMED"),
        @JsonSubTypes.Type(value = UncertainDecision.class, name = "UNCERTAIN"),
        @JsonSubTypes.Type(value = DeniedDecision.class, name = "DENIED")
})
public interface DominantDecision {

    /**
     * 是否已经形成可对外输出的确定性结论
     */
    boolean isFinal();

    /**
     * 若已裁决，返回被裁定的 claim
     */
    Optional<ClaimEvidence> decidedClaim();

    /**
     * 所有参与裁决的候选（用于解释）
     */
    List<ClaimEvidence> candidates();

    /**
     * 裁决原因（机器可读）
     */
    DecisionReason reason();

    @JsonProperty("type")
    String getType();   // ⭐ 新增


    static DominantDecision none() {
        return new UncertainDecision(
                List.of(),
                DecisionReason.NO_EVIDENCE
        );
    }


    static Builder builder() {
        return new Builder();
    }

    class Builder {

        private ClaimEvidence dominant;
        private EpistemicStatus epistemicStatus;
        private String decisionSource;
        private List<ClaimEvidence> candidates = List.of();

        public Builder dominant(ClaimEvidence c) {
            this.dominant = c;
            return this;
        }

        public Builder epistemicStatus(EpistemicStatus s) {
            this.epistemicStatus = s;
            return this;
        }

        public Builder decisionSource(String s) {
            this.decisionSource = s;
            return this;
        }

        public Builder candidates(List<ClaimEvidence> c) {
            this.candidates = c;
            return this;
        }

        public DominantDecision build() {

            if (dominant == null) {
                return DominantDecision.none();
            }

            DecisionReason reason = DecisionReason.graphDominant(decisionSource);

            return switch (epistemicStatus) {

                case CONFIRMED ->
                        new ConfirmedDecision(dominant, reason);

                case DENIED ->
                        new DeniedDecision(dominant, reason);

                default ->
                        new UncertainDecision(candidates, reason);
            };
        }
    }


}