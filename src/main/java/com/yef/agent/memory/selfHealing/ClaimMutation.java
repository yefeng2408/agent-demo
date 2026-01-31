package com.yef.agent.memory.selfHealing;

/**
 * 认知变更原语
 *
 * 	•	明确限定 系统允许的认知变更类型
 * 	•	防止隐式、不可审计的“野生修改”
 * 	•	将认知演化建模为 有限原语组合
 */
public sealed interface ClaimMutation permits
        ConfidenceAdjust,
        StatusOverride,
        RelationAttach {

    String claimKey();

}