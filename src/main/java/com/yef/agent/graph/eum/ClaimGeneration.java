package com.yef.agent.graph.eum;

/**
 *  claim来源版本，方便查询的时候做区分
 */
public enum ClaimGeneration {

    V3(false),        // 新一代推理，允许参与演化
    V2(true),         // 历史遗留，禁止参与演化
    IMPORTED(true);   // 人工/外部导入，禁止参与演化

    private final boolean legacy;

    ClaimGeneration(boolean legacy) {
        this.legacy = legacy;
    }

    public boolean isLegacy() {
        return legacy;
    }
}