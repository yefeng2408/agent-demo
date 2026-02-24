package com.yef.agent.memory.pipeline;

import com.alibaba.fastjson.JSON;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.event.EpistemicEvent;
import com.yef.agent.memory.event.factory.EpistemicEventFactory;
import com.yef.agent.memory.event.EpistemicEventPersistenceStage;
import com.yef.agent.memory.pipeline.eventRouter.EpistemicEventRouter;
import com.yef.agent.memory.pipeline.strategy.DeltaStrategy;
import com.yef.agent.memory.pipeline.strategy.StatusTransitionStage;
import com.yef.agent.memory.selfHealing.async.HandleEpistemicEventAsyncTask;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component
public class DefaultEpistemicDeltaPipeline implements EpistemicDeltaPipeline {

    private final List<DeltaStrategy> strategies;
    private final StatusTransitionStage statusStage;
    private final EpistemicEventPersistenceStage persistenceStage;
    private final HandleEpistemicEventAsyncTask handleEpistemicEventAsyncTask;
    private final EpistemicEventRouter eventRouter;

    public DefaultEpistemicDeltaPipeline(List<DeltaStrategy> strategies,
                                         StatusTransitionStage statusStage,
                                         EpistemicEventPersistenceStage persistenceStage,
                                         HandleEpistemicEventAsyncTask handleEpistemicEventAsyncTask,
                                         EpistemicEventRouter eventRouter
    ) {
        this.strategies = strategies;
        this.statusStage = statusStage;
        this.persistenceStage = persistenceStage;
        this.handleEpistemicEventAsyncTask = handleEpistemicEventAsyncTask;
        this.eventRouter = eventRouter;
    }

    @Override
    public EpistemicEvent execute(EpistemicContext ctx) {
        log.info("🔥 EXECUTE CALLED {}", ctx);
        // 1. 选择 delta 策略
        DeltaStrategy strategy = strategies.stream()
                .filter(s -> s.supports(ctx))
                .findFirst()
                .orElseThrow();

        // 2. 计算 delta
        List<ClaimDelta> deltas = strategy.apply(ctx);

        log.info(" [V3 FLOW] intent={} deltaCount={} shouldRecomputeDominant={} ",
                JSON.toJSONString(ctx.intentResult()), deltas.size(), true);
        // 3. 状态迁移
        statusStage.apply(ctx.userId(), deltas);

        // 4. 路由事件工厂（不再关心 SUPPORT / OPPOSE）
        EpistemicEventFactory factory = eventRouter.route(ctx);

        // 5. 构建事件
        EpistemicEvent event = factory.build(ctx, deltas);

        // 6. 持久化
        persistenceStage.persist(ctx.userId(), event);

        // 7. 异步 self-healing
        handleEpistemicEventAsyncTask.handle(ctx.userId(), event);

        return event;
    }



}
