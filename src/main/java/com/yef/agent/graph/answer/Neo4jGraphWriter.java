/*
package com.yef.agent.graph.answer;

import com.yef.agent.graph.ExtractedRelation;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import static org.neo4j.driver.Values.parameters;

@Component
public class Neo4jGraphWriter implements GraphWriter {

    private final Driver driver;

    public Neo4jGraphWriter(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void writeRelation(ExtractedRelation r) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {

                // 1️⃣ User
                tx.run("""
                    MERGE (u:User {id:$uid})
                """, parameters("uid", r.subjectId()));

                // 2️⃣ Predicate
                tx.run("""
                    MERGE (p:Predicate {name:$pred})
                """, parameters("pred", r.predicateType().name()));

                // 3️⃣ Object（Concept）
                tx.run("""
                    MERGE (o:Concept {id:$oid})
                    SET o.type = $type
                """, parameters(
                        "oid", r.objectId(),
                        "type", r.objectId().split(":")[0]
                ));

                // 4️⃣ Claim（稳定键）
                tx.run("""
                    MERGE (c:Claim {
                        subjectId: $uid,
                        predicate: $pred,
                        objectId: $oid,
                        quantifier: $q
                    })
                    SET c.polarity   = $pol,
                        c.confidence = $conf,
                        c.source     = $src,
                        c.updatedAt  = datetime()
                """, parameters(
                        "uid", r.subjectId(),
                        "pred", r.predicateType().name(),
                        "oid", r.objectId(),
                        "q", r.quantifier().name(),
                        "pol", r.polarity(),
                        "conf", r.confidence(),
                        "src", r.source().name()
                ));

                // 5️⃣ 关系
                tx.run("""
                    MATCH (u:User {id:$uid}), (c:Claim {subjectId:$uid, predicate:$pred, objectId:$oid})
                    MERGE (u)-[:ASSERTS]->(c)
                """, parameters(
                        "uid", r.subjectId(),
                        "pred", r.predicateType().name(),
                        "oid", r.objectId()
                ));

                return null;
            });
        }
    }
}*/
