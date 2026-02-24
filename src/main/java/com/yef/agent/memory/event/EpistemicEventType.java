package com.yef.agent.memory.event;

/**
 *
 * 表示一次「认知系统内部」发生的事件类型。
 *
 * ⚠️ 注意：
 * - 这是系统级行为（What the system does）
 * - 不是语义判断（What the user means）
 *
 * 每一种 EventType 通常会：
 * - 生成 ClaimDelta
 * - 影响 Claim 的 confidence / status
 * - 写入 StatusTransition / Event Log
 */
public enum EpistemicEventType {

    /**
     * SUPPORT
     *
     * 新输入对已有主张形成支持关系。
     *
     * 典型效果：
     * - 提升已有 claim 的 confidence
     * - 增加 supportCount / evidence
     *
     * 示例：
     * 用户：“是的，我确实有一辆特斯拉”
     */
    SUPPORT,

    /**
     * OPPOSE
     *
     * 新输入对已有主张形成反对关系，
     * 但尚不足以直接推翻。
     *
     * 典型效果：
     * - 降低已有 claim 的 confidence
     * - 建立 OPPOSES / COUNTERS 关系
     *
     * 示例：
     * 用户：“我不太确定我是否真的有特斯拉”
     */
    OPPOSE,

    /**
     * DECAY
     *
     * 无新输入，但由于时间衰减、长期未被支持，
     * 系统主动降低某些 claim 的置信度。
     *
     * 典型效果：
     * - confidence 随时间衰减
     * - 不改变 claim 内容，仅影响可信度
     *
     * 示例：
     * “三年前的个人信息，长期未被确认”
     */
    DECAY,

    /**
     * MERGE
     *
     * 多个语义等价或高度相似的 claim 被合并。
     *
     * 典型效果：
     * - 合并 evidence / confidence
     * - 减少图中的冗余节点
     *
     * 示例：
     * “我住在上海” vs “我的城市是上海”
     */
    MERGE,

    /**
     * OVERRIDE
     *
     * 新输入明确、强烈地推翻一个已 CONFIRMED 的旧主张。
     *
     * 典型效果：
     * - 将旧 claim 状态变为 DENIED / OVERRIDDEN
     * - 建立 SUPERSEDES / OVERRIDDEN_BY 关系
     *
     * 示例：
     * 用户：“我之前说错了，我其实没有特斯拉”
     */
    OVERRIDE,

    /**
     * REVISION
     *
     * 对已有主张进行修正，而非完全否定。
     *
     * 典型效果：
     * - 更新 claim 的部分字段
     * - 保留原主张，但调整其内容或约束
     *
     * 示例：
     * “我不是住在上海市中心，是在郊区”
     */
    REVISION
}