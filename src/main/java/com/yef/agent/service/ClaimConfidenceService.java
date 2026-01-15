package com.yef.agent.service;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import static org.neo4j.driver.Values.parameters;

@Component
public class ClaimConfidenceService {

    private final Driver driver;

    // 支持强度 & 反证强度
    private static final double SUPPORT_INC = 0.10;
    private static final double COUNTER_DEC = 0.12;

    public ClaimConfidenceService(Driver driver) {
        this.driver = driver;
    }

    public void applyAnswer(String userId, AnswerResult result) {
        if (result == null || !result.answered() || result.relation() == null) return;

        // 1) 把 relation 当作候选 claim
        ExtractedRelation r = result.relation();

        // 2) upsert 同向 claim（支持它）
        upsertAndSupport(userId, r, SUPPORT_INC);

        // 3) 如果你愿意做“反证打压”：打压 polarity 相反的 claim
        downscaleOpposite(userId, r, COUNTER_DEC);

        // 4) 把 Answer 证据链挂上（如果你已经写 Answer 节点了）
        // linkAnswerSupportsClaim(...)
    }

    private void upsertAndSupport(String userId, ExtractedRelation r, double inc) {
        String cypher = """
                MERGE (u:User {id:$uid})
                MERGE (u)-[:ASSERTS]->(c:Claim {
                  subjectId:$sid,
                  predicate:$pred,
                  objectId:$oid,
                  quantifier:$q,
                  polarity:$pol
                })
                ON CREATE SET
                  c.confidence = $baseConf,
                  c.supportCount = 1,
                  c.source = $source,
                  c.batch = $batch,
                  c.createdAt = datetime(),
                  c.updatedAt = datetime(),
                  c.lastSupportedAt = datetime()
                ON MATCH SET
                  c.confidence = CASE
                    WHEN c.confidence IS NULL THEN $baseConf
                    WHEN (c.confidence * 0.995) + $inc > 0.99 THEN 0.99
                    ELSE (c.confidence * 0.995) + $inc
                  END,
                  c.supportCount = coalesce(c.supportCount, 0) + 1,
                  c.updatedAt = datetime(),
                  c.lastSupportedAt = datetime()
                  """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "sid", r.subjectId(),
                    "pred", r.predicateType().name(),
                    "oid", r.objectId(),
                    "q", r.quantifier().name(),
                    "pol", r.polarity(),
                    "inc", inc,
                    "baseConf", Math.min(r.confidence(), 0.7),
                    "source", r.source().name(),
                    "batch", "answer-loop"
            )).consume());

        }

    }

    private void downscaleOpposite(String userId, ExtractedRelation r, double dec) {
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim)
        WHERE c.subjectId=$sid
          AND c.predicate=$pred
          AND c.objectId=$oid
          AND c.quantifier=$q
          AND c.polarity <> $pol
        SET c.confidence = CASE
            WHEN ((coalesce(c.confidence, 0.5) * 0.995) - $dec) < 0.0 THEN 0.0
            ELSE ((coalesce(c.confidence, 0.5) * 0.995) - $dec)
            END,
            c.updatedAt = datetime()
        """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "sid", r.subjectId(),
                    "pred", r.predicateType().name(),
                    "oid", r.objectId(),
                    "q", r.quantifier().name(),
                    "pol", r.polarity(),
                    "dec", dec
            )).consume());
        }
    }


}