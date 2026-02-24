package com.yef.agent.memory.selfHealing;

import com.yef.agent.graph.answer.ClaimEvidence;

import java.util.List;

public interface SelfCorrectionResolver {

    /**
     *
     * 判断是否发生“自我修正”，并返回修正建议
     *
     * Resolver 的职责边界（非常关键）：
     * 它不允许做的事 ❌：
     * 	•	不得直接修改 claim
     * 	•	不得裁决谁是真的
     * 	•	不得删除任何 claim
     *
     * 它唯一能做的事 ✅：
     * 	•	判断：这次冲突值不值得“留下痕迹”
     * 	•	生成：一组 ClaimMutation
     * 	•	给出：DominantClaimHint（非强制）
     * @param newClaim  本次新声明（已经写入 slot 后的快照）
     * @param existingClaims  同 predicate / object / quantifier 的已有声明
     * @return
     */
    SelfCorrectionResult resolve(ClaimEvidence newClaim, List<ClaimEvidence> existingClaims);


}
