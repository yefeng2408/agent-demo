package com.yef.agent.memory.narrative;

import com.yef.agent.memory.EpistemicStatus;

import java.time.Duration;

/*
 * <p>
 * 作用：
 * Narrative 层对当前 DominantClaim 的叙事态评分快照。
 * <p>
 * 注意：
 * 这不是认知事实，也不会写入 Neo4j。
 * 仅用于 ExplainableAnswerBuilder 生成回答时，
 * 决定语气、可信度表达、ownership 等叙事策略。
 * <p>
 */
public record DominantNarrativeScore(

        /*Dominant 的“叙事有效置信度”。
         *
         * ≠ Claim 原始 confidence
         * ≠ StatusTransition 的判定阈值
         *
         * ✔ 已经过：
         *   - dominant 持续时间衰减
         *   - override 冲击
         *   - 最近挑战修正
         *
         * 用途：
         * NarrativeTone 决策强弱
         */
        double effectiveConfidence,

        EpistemicStatus status,

        /* 当前 Dominant 已经持续的时间长度（Duration）。
         * ✔ 不是 claim 创建时间
         * ✔ 不是 statusChangedAt
         *
         * 用途：
         * - 判断稳定性
         * - 影响语气：
         * 新 dominant → 谨慎表达
         * 长期 dominant → 更确定表达
         */
        Duration dominanceAge,

        /* Dominant 最近是否被 override 或状态迁移。
         *
         * 作用：
         * 防止刚刚被挑战的 dominant 仍然输出强语气。
         */
        boolean recentlyChallenged
) {
}
