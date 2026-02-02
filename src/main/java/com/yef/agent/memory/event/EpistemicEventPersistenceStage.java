package com.yef.agent.memory.event;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class EpistemicEventPersistenceStage {

    private final Driver driver;

    public EpistemicEventPersistenceStage(Driver driver) {
        this.driver = driver;
    }

    public void persist(String userId, EpistemicEvent event) {
        persistEpistemicEvent(userId, event);
    }

    public void persistEpistemicEvent(String userId, EpistemicEvent event) {
        String cypher = """
        MATCH (u:User {id: $userId})
        
        CREATE (e:EpistemicEvent {
          id: $eventId,
          type: $type,
          at: datetime($at),
          reason: $reason,
          triggerKey: $triggerKey
        })
        
        CREATE (u)-[:EMITTED]->(e)
        
        WITH e
        UNWIND $deltas AS d
        
        MATCH (c:Claim {key: d.claimKey})
        
        CREATE (e)-[:AFFECTS {
          before: d.before,
          after: d.after,
          delta: d.delta,
          direction: d.direction
        }]->(c)
        """;
        //List<Map<String, Object>>
        List deltas = event.deltas().stream()
                .map(d -> Map.of(
                        "claimKey", d.claimKey(),
                        "before", d.beforeConfidence(),
                        "after", d.afterConfidence(),
                        "delta", d.delta(),
                        "direction", d.direction().name()
                ))
                .toList();

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Map.of(
                        "userId", userId,
                        "eventId", event.eventId(),
                        "type", event.type().name(),
                        "at", event.at().toString(),
                        "reason", event.reason(),
                        "triggerKey", event.triggerKey(),
                        "deltas", deltas
                ));
                return null;
            });
        }
    }


}
