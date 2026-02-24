package com.yef.agent.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.SourceAdapter;
import com.yef.agent.memory.EpistemicStatus;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.TypeSystem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class Neo4jMappingSupport {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public <T> T mapNode(Node node, Class<T> clazz) {
        if (node == null) {
            return null;
        }

        Map<String, Object> props = new HashMap<>();

        node.asMap().forEach((k, v) -> {
            if (v instanceof Value value) {
                props.put(k, value.asObject());
            } else {
                props.put(k, v);
            }
        });

        return objectMapper.convertValue(props, clazz);
    }

    public ClaimEvidence mapToClaimEvidence(TypeSystem typeSystem, Record record) {

        Node rec = record.get("c").asNode();
        String predStr = rec.get("predicate").asString();
        String qStr = rec.get("quantifier").asString();
        String statusStr = rec.get("epistemicStatus").isNull() ? null : rec.get("epistemicStatus").asString();
        EpistemicStatus epistemicStatus = EpistemicStatus.fromGraph(statusStr);
        double momentum = rec.containsKey("momentum")
                ? rec.get("momentum").asDouble()
                : 0.0d;

        Instant lastMomentumAt =
                rec.get("lastMomentumAt").isNull()
                        ? null
                        : rec.get("lastMomentumAt")
                        .asZonedDateTime()
                        .toInstant();

        ClaimEvidence claimEvidence = new ClaimEvidence(
                rec.get("elementId").asString(),
                rec.get("subjectId").asString(),
                PredicateType.valueOf(predStr),
                rec.get("objectId").asString(),
                Quantifier.valueOf(qStr),
                rec.get("polarity").asBoolean(),
                epistemicStatus,
                rec.get("confidence").asDouble(),
                rec.get("source").isNull() ? null : SourceAdapter.fromRaw(rec.get("source").asString()),
                rec.get("batch").isNull() ? null : rec.get("batch").asString(),
                rec.get("updatedAt").isNull() ? null : rec.get("updatedAt").asZonedDateTime().toInstant(),
                rec.get("lastStatusChangedAt").isNull() ? null : rec.get("lastStatusChangedAt").asZonedDateTime().toInstant(),
                rec.get("priority").asInt(),
                momentum,
                lastMomentumAt

        );
        return claimEvidence;
    }


}