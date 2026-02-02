package com.yef.agent.memory.event;

import com.yef.agent.memory.ClaimDelta;

import java.time.Instant;
import java.util.List;

/**
 * 表示一次“支持型认知事件”：
 * 本轮输入对既有主张提供支持性证据。
 */
public record SupportEvent(

        /** 事件唯一标识，用于事件溯源与回放 */
        String eventId,

        /** 触发该认知事件的用户 */
        String userId,

        /** 事件发生时间 */
        Instant at,

        /**
         * 本轮输入形成的触发立场（ExtractedRelation 的 key）
         * 表示“是什么话触发了这次支持”
         */
        String triggerKey,

        /** 人类可读的事件原因说明（如 support / inferred / implicit） */
        String reason,

        /**
         * 被支持的既有 claim（Citation / ClaimEvidence 的 key）
         * 表示认知图中被增强的主张
         */
        String supportedClaimKey,

        /**
         * 本次支持事件产生的具体认知变更列表
         * 例如置信度提升、关系附加等
         */
        List<ClaimDelta> deltas

) implements EpistemicEvent {

    @Override
    public EpistemicEventType type() {
        return EpistemicEventType.SUPPORT;
    }
}