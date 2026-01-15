package com.yef.agent.graph.writer;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.Citation;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

import static org.neo4j.driver.Values.parameters;

@Component
public class Neo4jGraphWriter implements GraphWriter {

    private final Driver driver;

    public Neo4jGraphWriter(Driver driver) {
        this.driver = driver;
    }

    /**
     * writeAnswer 必须是“无副作用事实写入”，不可生成 Claim
     * @param userId
     * @param decision
     * @param citations
     * @param answerText
     */
    @Override
    public void writeAnswer(String userId, ExtractedRelation decision,
            List<Citation> citations, String answerText) {

        if (citations == null || citations.isEmpty()) {
            throw new IllegalStateException("Answer must cite at least one Claim");
        }
        List<Map<String, String>> citationMaps = citations.stream()
                .map(c -> Map.of(
                        "subjectId", c.subjectId(),
                        "predicate", c.predicate(),
                        "objectId", c.objectId()
                )).toList();
        String cypher = """
                MATCH (u:User {id:$uid})
                
                CREATE (a:Answer {
                  id: randomUUID(),
                  text: $text,
                  predicate: $pred,
                  polarity: $polarity,
                  confidence: $conf,
                  createdAt: datetime()
                })
                
                CREATE (u)-[:RECEIVED]->(a)
                
                WITH a
                UNWIND $citations AS c
                MATCH (e:Claim {
                  subjectId: c.subjectId,
                  predicate: c.predicate,
                  objectId: c.objectId
                })
                MERGE (a)-[:CITES]->(e)
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                Result result = tx.run(cypher, parameters(
                        "uid", userId,
                        "text", answerText,
                        "pred", decision.predicateType().name(),
                        "polarity", decision.polarity(),
                        "conf", decision.confidence(),
                        "citations", citationMaps
                ));
                result.consume(); // 🔥 关键
                return null;      // executeWrite 要一个返回值
            });
        }
    }
}