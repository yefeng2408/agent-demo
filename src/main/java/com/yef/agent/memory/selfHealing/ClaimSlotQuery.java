package com.yef.agent.memory.selfHealing;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.memory.EpistemicStatus;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import java.util.List;
import static org.neo4j.driver.Values.parameters;

@Component
public class ClaimSlotQuery {

    private final Driver driver;

    public ClaimSlotQuery(Driver driver) {
        this.driver = driver;
    }

    /** 查询同一个 slot（四元组）下的所有 claim（包含 polarity=true/false） */
    public List<ClaimEvidence> findAllInSlot(String userId,
                                             String subjectId,
                                             PredicateType predicate,
                                             String objectId,
                                             Quantifier quantifier) {
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
          subjectId:$sid,
          predicate:$pred,
          objectId:$oid,
          quantifier:$q,
          legacy:false
        })
        RETURN c
        ORDER BY c.updatedAt DESC
        """;

        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "sid", subjectId,
                            "pred", predicate.name(),
                            "oid", objectId,
                            "q", quantifier.name()
                    )).list(r -> {
                        var c = r.get("c").asNode();
                        return new ClaimEvidence(
                                c.get("subjectId").asString(),
                                PredicateType.valueOf(c.get("predicate").asString()),
                                c.get("objectId").asString(),
                                Quantifier.valueOf(c.get("quantifier").asString()),
                                c.get("polarity").asBoolean(),
                                c.get("epistemicStatus").isNull() ? null
                                        : EpistemicStatus.valueOf(c.get("epistemicStatus").asString()),
                                c.get("confidence").isNull() ? 0.0 : c.get("confidence").asDouble(),
                                c.get("source").isNull() ? null : Source.valueOf(c.get("source").asString()),
                                c.get("batch").isNull() ? null : c.get("batch").asString(),
                                c.get("updatedAt").isNull() ? null : c.get("updatedAt").asZonedDateTime().toInstant(),
                                c.get("lastStatusChangedAt").isNull() ? null : c.get("lastStatusChangedAt").asZonedDateTime().toInstant(),
                                c.get("priority").isNull() ? 0 : c.get("priority").asInt()
                        );
                    })
            );
        }
    }


}