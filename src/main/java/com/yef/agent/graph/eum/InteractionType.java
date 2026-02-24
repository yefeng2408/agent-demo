package com.yef.agent.graph.eum;

public enum InteractionType {

    /**
     * ASK：查询当前认知状态
     * - 只读
     * - 不产生 Claim
     * - 不参与博弈
     */
    ASK,

    /**
     * ASSERT：新增或加强一个主张
     * - 写入 Claim
     * - 可能触发状态迁移
     */
    ASSERT,

    /**
     * CHALLENGE：反驳 / 否定已有主张
     * - 必须指向已有 Claim
     * - 一定进入博弈
     */
    CHALLENGE

}