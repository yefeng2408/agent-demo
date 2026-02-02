package com.yef.agent.memory.event;

import com.yef.agent.memory.ClaimDelta;
import java.time.Instant;
import java.util.List;

/**
 * 表示一次“对立型认知事件”：
 * 新输入引发对既有主张的挑战，并触发博弈与状态调整。
 */
public record OpposeEvent(

        /** 事件唯一标识，用于认知事件回放 */
        String eventId,

        /** 触发该认知事件的用户 */
        String userId,

        /** 事件发生时间 */
        Instant at,

        /**
         * 本轮输入形成的触发立场（ExtractedRelation 的 key）
         * 表示用户“说了什么”导致了这次冲突
         */
        String triggerKey,

        /** 人类可读的事件原因说明（如 oppose / deny / self-correction） */
        String reason,

        /**
         * 被挑战的既有主张（旧 claim）
         * 即当前认知图中的 dominant claim
         */
        String dominantClaimKey,

        /**
         * 事件发生前 dominant claim 的置信度快照
         * 用于审计与可解释展示
         */
        double dominantDelta,

        /**
         * 为认知博弈构造的反事实主张（polarity 翻转后的 canonical claim）
         * 该 claim 可能并非用户原话，而是结构化对立立场
         */
        String oppositeClaimKey,

        /**
         * 反事实主张在本次事件中的初始置信度
         * 通常用于对比与状态迁移判断
         */
        double oppositeDelta,

        /**
         * 本次对立事件产生的认知变更列表
         * 包括置信度衰减、状态迁移、关系附加等
         */
        List<ClaimDelta> deltas

) implements EpistemicEvent {

    @Override
    public EpistemicEventType type() {
        return EpistemicEventType.OPPOSE;
    }
}