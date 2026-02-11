package com.yef.agent.memory.decision.biz;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.decision.DecisionReason;
import java.util.List;
import java.util.Optional;

/**
 * 裁决结果顶层接口
 */
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
}