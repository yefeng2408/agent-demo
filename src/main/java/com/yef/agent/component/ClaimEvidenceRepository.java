package com.yef.agent.component;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.graph.eum.SourceAdapter;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.PredicateKey;
import org.jetbrains.annotations.NotNull;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import static org.neo4j.driver.Values.parameters;

@Component
public class ClaimEvidenceRepository {

    private final Driver driver;
    private final KeyCodec keyCodec;

    public ClaimEvidenceRepository(Driver driver,KeyCodec keyCodec) {
        this.driver = driver;
        this.keyCodec = keyCodec;
    }

    public ClaimEvidence loadByKey(String userId, String claimKey) {
        KeyCodec.DecodedKey key = keyCodec.decode(claimKey);
        return loadClaimEvidence(
                userId,
                key.subjectId(),
                key.predicate(),
                key.objectId(),
                key.quantifier(),
                key.polarity()
        );
    }



    public ClaimEvidence loadClaimEvidence(
            String userId,
            String subjectId,
            PredicateType predicate,
            String objectId,
            Quantifier quantifier,
            boolean polarity) {
        return getClaimEvidence(userId, subjectId, predicate, objectId, quantifier, polarity, driver);
    }

    @NotNull
    public static ClaimEvidence getClaimEvidence(String userId, String subjectId, PredicateType predicate, String objectId, Quantifier quantifier, boolean polarity, Driver driver) {
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
          subjectId:$sid,
          predicate:$pred,
          objectId:$oid,
          quantifier:$q,
          polarity:$pol
        })
        RETURN
          c.subjectId AS subjectId,
          c.predicate AS predicate,
          c.objectId AS objectId,
          c.quantifier AS quantifier,
          c.polarity AS polarity,
          c.epistemicStatus AS epistemicStatus,
          coalesce(c.confidence, 0.5) AS confidence,
          c.source AS source,
          c.batch AS batch,
          c.updatedAt AS updatedAt,
          coalesce(c.priority, 0) AS priority
        """;
        try (Session session = driver.session()) {
            var rec = session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "sid", subjectId,
                            "pred", predicate.name(),
                            "oid", objectId,
                            "q", quantifier.name(),
                            "pol", polarity
                    )).single()
            );
            // 注意：Neo4j 返回的类型你可按你项目里已有的工具再封装一下
            String predStr = rec.get("predicate").asString();
            String qStr = rec.get("quantifier").asString();
            String statusStr = rec.get("epistemicStatus").isNull() ? null : rec.get("epistemicStatus").asString();
            EpistemicStatus epistemicStatus = EpistemicStatus.fromGraph(statusStr);

            return new ClaimEvidence(
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
                    rec.get("priority").asInt()
            );
        }
    }


}
