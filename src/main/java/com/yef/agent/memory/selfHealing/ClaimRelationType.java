package com.yef.agent.memory.selfHealing;

/**
 * ClaimRelationType 表示两个 Claim 之间的「认知因果关系」。
 *
 * <p>
 * 它用于描述：为什么一个 Claim 的出现，会对另一个 Claim 的可信度、状态或裁决倾向产生影响。
 * </p>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>RelationType 描述的是「认知因果」，不是数据库结构关系</li>
 *   <li>RelationType 不直接等同于状态改变（CONFIRMED / OVERRIDDEN）</li>
 *   <li>RelationType 必须是可解释、可回放、可审计的</li>
 * </ul>
 *
 * <h2>使用约束</h2>
 * <ul>
 *   <li>RelationType 只能由 Self-Healing / Self-Correction 过程生成</li>
 *   <li>Answer 阶段（裁决 / 三态回答）禁止写入 RelationType</li>
 *   <li>RelationType 本身不等同于“最终结论”，只是演化痕迹</li>
 * </ul>
 *
 * <h2>各类型语义说明</h2>
 */
public enum ClaimRelationType {

    /**
     * SUPPORTS：
     * 表示 fromClaim 为 toClaim 提供支持证据，
     * 会增强 toClaim 的可信度，但不会否定其它 Claim。
     *
     * <p>常见场景：</p>
     * <ul>
     *   <li>用户多次确认同一事实</li>
     *   <li>不同来源指向同一结论</li>
     * </ul>
     *
     * <p>系统影响：</p>
     * <ul>
     *   <li>可能触发 ConfidenceAdjust（提升）</li>
     *   <li>不会直接改变 epistemicStatus</li>
     * </ul>
     */
    SUPPORTS,

    /**
     * OPPOSES：
     * 表示 fromClaim 在逻辑上否定 toClaim，
     * 二者不能同时为真，但不包含时间替代语义。
     *
     * <p>常见场景：</p>
     * <ul>
     *   <li>同一 predicate + object，polarity 相反</li>
     *   <li>用户产生自相矛盾陈述</li>
     * </ul>
     *
     * <p>系统影响：</p>
     * <ul>
     *   <li>Self-Healing 的主要触发来源</li>
     *   <li>可能导致 WEAKENS 或 SUPERSEDES 的进一步判断</li>
     * </ul>
     */
    OPPOSES,

    /**
     * SUPERSEDES：
     * 表示 fromClaim 在时间或事实层面上「覆盖 / 替代」toClaim，
     * 即：toClaim 曾经成立，但当前已被更新。
     *
     * <p><b>注意：</b>该关系必须具备明确的时间或状态迁移语义。</p>
     *
     * <p>常见场景：</p>
     * <ul>
     *   <li>“我以前有，但现在没有”</li>
     *   <li>状态明确发生变更</li>
     * </ul>
     *
     * <p>系统影响：</p>
     * <ul>
     *   <li>允许触发 StatusOverride（旧 Claim 被标记为 OVERRIDDEN）</li>
     *   <li>强烈影响后续裁决倾向</li>
     * </ul>
     */
    SUPERSEDES,

    /**
     * WEAKENS：
     * 表示 fromClaim 不直接否定 toClaim，
     * 但降低其可信度。
     *
     * <p>常见场景：</p>
     * <ul>
     *   <li>用户语气变得不确定</li>
     *   <li>事实条件发生变化但未确认</li>
     * </ul>
     *
     * <p>系统影响：</p>
     * <ul>
     *   <li>通常触发 ConfidenceAdjust（下降）</li>
     *   <li>不会改变 epistemicStatus</li>
     * </ul>
     */
    WEAKENS,

    /**
     * DERIVED_FROM：
     * 表示 fromClaim 是基于 toClaim 推导或派生出来的结论。
     *
     * <p>常见场景：</p>
     * <ul>
     *   <li>组合事实推论</li>
     *   <li>AI 自动生成的总结性 Claim</li>
     * </ul>
     *
     * <p>系统影响：</p>
     * <ul>
     *   <li>若源 Claim 被削弱或覆盖，派生 Claim 应同步受影响</li>
     * </ul>
     */
    DERIVED_FROM,

    /**
     * CONTEXT_OF：
     * 表示 fromClaim 与 toClaim 仅存在对话或上下文关联，
     * 不具备逻辑因果或裁决意义。
     *
     * <p>常见场景：</p>
     * <ul>
     *   <li>举例、类比、说明性内容</li>
     * </ul>
     *
     * <p>系统影响：</p>
     * <ul>
     *   <li>不会参与 Self-Healing</li>
     *   <li>不会参与三态裁决</li>
     * </ul>
     */
    CONTEXT_OF
}