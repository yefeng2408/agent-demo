package com.yef.agent.memory.factory;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.event.SupportEvent;
import com.yef.agent.memory.pipeline.EpistemicContext;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class SupportEventFactory implements EpistemicEventFactory{

    private final KeyCodec keyCodec;

    public SupportEventFactory(KeyCodec keyCodec) {
        this.keyCodec = keyCodec;
    }

    @Override
    public EpistemicEvent build(EpistemicContext ctx, List<ClaimDelta> deltas) {
        EpistemicEvent epistemicEvent =new SupportEvent(
                UUID.randomUUID().toString(),
                ctx.userId(),
                Instant.now(),
                keyCodec.buildExtractRelKey(ctx.extracted()),    // ✅ triggerKey：新抽取的 relation
                "support",
                keyCodec.buildCitationKey(ctx.dominant()),  // ✅ 被支持的 claim
                deltas);
        //persistEpistemicEvent(userId,epistemicEvent);
        return epistemicEvent;
    }

}
