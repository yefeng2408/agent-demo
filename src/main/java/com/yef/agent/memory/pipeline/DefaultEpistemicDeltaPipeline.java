package com.yef.agent.memory.pipeline;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.factory.EpistemicEventFactory;
import com.yef.agent.memory.factory.EpistemicEventPersistenceStage;
import com.yef.agent.memory.pipeline.strategy.DeltaStrategy;
import com.yef.agent.memory.pipeline.strategy.StatusTransitionStage;
import com.yef.agent.memory.selfHealing.ClaimSlotQuery;
import com.yef.agent.memory.selfHealing.SelfCorrectionResolver;
import com.yef.agent.memory.selfHealing.SelfCorrectionResult;
import com.yef.agent.memory.selfHealing.async.HandleEpistemicEventAsyncTask;
import org.springframework.stereotype.Component;
import java.util.List;



@Component
public class DefaultEpistemicDeltaPipeline implements EpistemicDeltaPipeline {

    private final List<DeltaStrategy> strategies;
    private final StatusTransitionStage statusStage;
    private final EpistemicEventFactory eventFactory;
    private final EpistemicEventPersistenceStage persistenceStage;
    private final SelfCorrectionResolver selfCorrectionResolver;
    private final HandleEpistemicEventAsyncTask handleEpistemicEventAsyncTask;
    private final ClaimSlotQuery claimSlotQuery;
    private final KeyCodec keyCodec;


    public DefaultEpistemicDeltaPipeline(List<DeltaStrategy> strategies,
                                         StatusTransitionStage statusStage,
                                         EpistemicEventFactory eventFactory,
                                         EpistemicEventPersistenceStage persistenceStage,
                                         SelfCorrectionResolver selfCorrectionResolver,
                                         HandleEpistemicEventAsyncTask handleEpistemicEventAsyncTask,
                                         ClaimSlotQuery claimSlotQuery,
                                         KeyCodec keyCodec
    ) {
        this.strategies = strategies;
        this.statusStage = statusStage;
        this.eventFactory = eventFactory;
        this.persistenceStage = persistenceStage;
        this.selfCorrectionResolver = selfCorrectionResolver;
        this.handleEpistemicEventAsyncTask = handleEpistemicEventAsyncTask;
        this.claimSlotQuery = claimSlotQuery;
        this.keyCodec = keyCodec;
    }

    @Override
    public EpistemicEvent execute(EpistemicContext ctx) {
        DeltaStrategy strategy = strategies.stream()
                .filter(s -> s.supports(ctx))
                .findFirst()
                .orElseThrow();
        List<ClaimDelta> deltas = strategy.apply(ctx);

        statusStage.apply(ctx.userId(), deltas);

        EpistemicEvent event = eventFactory.build(ctx, deltas);

        persistenceStage.persist(ctx.userId(), event);

        // 不做任何 self-healing 逻辑判断
        handleEpistemicEventAsyncTask.handle(ctx.userId(), event);
        return event;
    }


}
