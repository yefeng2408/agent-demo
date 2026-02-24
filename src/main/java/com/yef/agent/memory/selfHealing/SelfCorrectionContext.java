package com.yef.agent.memory.selfHealing;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.event.EpistemicEventType;
import java.time.Instant;
import java.util.List;

/**
 * 新旧claim上下文
 *      在某个时间点，一个新主张进来，它和哪些旧主张发生了什么关系？
 * @param userId
 * @param newClaim
 * @param conflictedClaims
 * @param eventType
 * @param reason
 * @param at
 */
public record SelfCorrectionContext(

        String userId,

        // ① 本次事件触发的“新主张”
        ClaimEvidence newClaim,

        // ② 与之发生认知冲突的旧主张（已 canonical）
        List<ClaimEvidence> conflictedClaims,

        // ③ 事件类型（OPPOSE / SUPPORT / ...)
        EpistemicEventType eventType,

        // ④ 本次修复的触发原因（给 explain / audit 用）
        String reason,

        // ⑤ 时间（用于衰减、审计）
        Instant at

        /*	•	userId
                •	triggerKey：用户本轮输入（newR 的 key）
                •	dominantKey：被裁决命中的 claim key（dominant）
                •	relation：SUPPORT / OPPOSE
                •	deltas：本次演化对哪些 claim 做了什么（ClaimDelta 列表）
                •	eventId / at / reason：审计信息*/

) {}