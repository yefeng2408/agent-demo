package com.yef.agent.memory.selfHealing.repository;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.memory.EpistemicStatus;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.types.Node;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ClaimRepository {

    private final Driver driver;

    public ClaimRepository(Driver driver) {
        this.driver = driver;
    }


    public Optional<ClaimEvidence> findByKey(KeyCodec.DecodedKey key) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                String cypher = """
                MATCH (c:Claim)
                WHERE c.subjectId = $subjectId
                  AND c.predicate = $predicate
                  AND c.objectId = $objectId
                  AND c.quantifier = $quantifier
                  AND c.polarity = $polarity
                RETURN c
                LIMIT 1
            """;

                Map<String, Object> params = Map.of(
                        "subjectId", key.subjectId(),
                        "predicate", key.predicate().name(),
                        "objectId", key.objectId(),
                        "quantifier", key.quantifier().name(),
                        "polarity", key.polarity()
                );

                Result result = tx.run(cypher, params);
                if (!result.hasNext()) {
                    return Optional.empty();
                }
                Node node = result.next().get("c").asNode();
                return Optional.of(mapToClaimEvidence(node));
            });
        }
    }

    public List<ClaimEvidence> findBySlot(
            String userId,
            String subjectId,
            String predicate,
            String objectId,
            Quantifier quantifier,
            boolean polarity) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                String cypher = """
                MATCH (u:User {id: $userId})-[:OWNS]->(c:Claim)
                WHERE c.subjectId = $subjectId
                  AND c.predicate = $predicate
                  AND c.objectId = $objectId
                  AND c.quantifier = $quantifier
                  AND c.polarity = $polarity
                RETURN c
            """;

                Map<String, Object> params = Map.of(
                        "userId", userId,
                        "subjectId", subjectId,
                        "predicate", predicate,
                        "objectId", objectId,
                        "quantifier", quantifier.name(),
                        "polarity", polarity
                );

                Result result = tx.run(cypher, params);
                List<ClaimEvidence> claims = new ArrayList<>();

                while (result.hasNext()) {
                    Node node = result.next().get("c").asNode();
                    claims.add(mapToClaimEvidence(node));
                }
                return claims;
            });
        }
    }


    private ClaimEvidence mapToClaimEvidence(Node c) {
        return new ClaimEvidence(
                c.get("elementId").asString(),
                c.get("subjectId").asString(),
                PredicateType.valueOf(c.get("predicate").asString()),
                c.get("objectId").asString(),
                Quantifier.valueOf(c.get("quantifier").asString()),
                c.get("polarity").asBoolean(),
                EpistemicStatus.valueOf(c.get("epistemicStatus").asString()),
                c.get("confidence").asDouble(),
                Source.valueOf(c.get("source").asString()),
                c.get("batch").asString(),
                c.get("updatedAt").asZonedDateTime().toInstant(),
                c.get("lastStatusChangedAt").asZonedDateTime().toInstant(),
                c.get("priority").asInt(),
                c.get("momentum").asDouble(),
                c.get("lastMomentumAt").asZonedDateTime().toInstant()
        );
    }

}