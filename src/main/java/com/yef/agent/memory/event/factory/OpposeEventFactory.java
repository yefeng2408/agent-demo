package com.yef.agent.memory.event.factory;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.event.EpistemicEventType;
import com.yef.agent.memory.event.OpposeEvent;
import com.yef.agent.memory.pipeline.EpistemicContext;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OpposeEventFactory implements EpistemicEventFactory {

    private final KeyCodec keyCodec;

    public OpposeEventFactory(KeyCodec keyCodec) {
        this.keyCodec = keyCodec;
    }

    @Override
    public EpistemicEventType supportsType() {
        return EpistemicEventType.OPPOSE;
    }

    @Override
    public EpistemicEvent build(EpistemicContext ctx, List<ClaimDelta> deltas) {

        EpistemicEvent epistemicEvent =new OpposeEvent(
                UUID.randomUUID().toString(),
                ctx.userId(),
                Instant.now(),
                keyCodec.buildExtractRelKey(ctx.extracted()),    // ✅ triggerKey：新抽取的 relation
                "oppose",
                keyCodec.buildCitationKey(ctx.dominant()),  // ✅ 被挑战的 claim
                ctx.dominant().confidence(),
                keyCodec.buildExtractRelKey(ctx.opposite()), //挑战者
                ctx.opposite().confidence(),
                deltas);
        return epistemicEvent;
    }
}
