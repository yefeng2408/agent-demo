package com.yef.agent.memory.pipeline.eventRouter;

import com.yef.agent.memory.event.EpistemicEventType;
import com.yef.agent.memory.event.factory.EpistemicEventFactory;
import com.yef.agent.memory.pipeline.EpistemicContext;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DefaultEpistemicEventRouter implements EpistemicEventRouter {

    private final Map<EpistemicEventType, EpistemicEventFactory> factoryByType;

    public DefaultEpistemicEventRouter(List<EpistemicEventFactory> factories) {
        this.factoryByType = factories.stream()
                .collect(Collectors.toMap(
                        EpistemicEventFactory::supportsType,
                        Function.identity()
                ));
    }

    @Override
    public EpistemicEventFactory route(EpistemicContext ctx) {
        EpistemicEventType type = resolveType(ctx);
        EpistemicEventFactory factory = factoryByType.get(type);

        if (factory == null) {
            throw new IllegalStateException("No factory for eventType=" + type);
        }
        return factory;
    }

    private EpistemicEventType resolveType(EpistemicContext ctx) {
        return switch (ctx.semanticRelation()) {
            case SUPPORT -> EpistemicEventType.SUPPORT;
            case OPPOSE  -> EpistemicEventType.OPPOSE;
            case NEUTRAL -> null; // 或 Optional.empty()
        };
    }
}