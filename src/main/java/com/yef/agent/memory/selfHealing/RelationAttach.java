package com.yef.agent.memory.selfHealing;

/**
 * 把 claim 之间的关系写进图
 *
 * 整个系统 “可解释性”的根基：
 * 	•	SUPPORT
 * 	•	OPPOSE
 * 	•	OVERRIDDEN_BY
 * 	•	CORRECTS
 * 它让未来的 Neo4j 可视化 不只是状态图，而是认知路径图。
 *
 * @param fromClaimId
 * @param toClaimId
 * @param relationType
 */
public record RelationAttach(
        String fromClaimId,
        String toClaimId,
        ClaimRelationType relationType
) implements ClaimMutation {

    @Override
    public String claimKey() {
        return "";
    }
}