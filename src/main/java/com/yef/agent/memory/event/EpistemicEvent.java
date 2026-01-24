package com.yef.agent.memory.event;

import com.yef.agent.memory.ClaimDelta;

import java.time.Instant;
import java.util.List;

/**
 * 认知演化事件的共同接口
 */
public interface EpistemicEvent {
    //事件唯一 ID
    String eventId();
    //属于哪个认知体
    String userId();
    //SUPPORT / OPPOSE / DECAY / …
    EpistemicEventType type();
    //发生时间
    Instant at();
    //人类可读原因
    String reason();
    //触发本次事件的证据
    String evidenceKey();
    //对哪些 Claim 产生了什么影响
    List<ClaimDelta> deltas();
}
