package com.yef.agent.repository.impl;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.repository.StatusTransitionRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Repository;
import static org.neo4j.driver.Values.parameters;

@Repository
public class StatusTransitionRepositoryImpl implements StatusTransitionRepository {

    private final Driver driver;
    private final KeyCodec keyCodec;

    public StatusTransitionRepositoryImpl(Driver driver, KeyCodec keyCodec) {
        this.driver = driver;
        this.keyCodec = keyCodec;
    }

    //写状态迁移记录节点
    @Override
    public void writeStatusTransition(String userId,
                                      String claimKey,
                                      EpistemicStatus from,
                                      EpistemicStatus to,
                                      ClaimDelta delta) {

        KeyCodec.DecodedKey c = keyCodec.decode(claimKey);
        String cypher = """
                  MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim { 
                    c.userId:$uid,
                    c.subjectId = $sid,
                    c.predicate = $pred,
                    c.objectId = $oid,
                    c.quantifier = $q,
                    c.polarity = $pol
                  })
                  MERGE (t:StatusTransition {id:$tid})
                  SET t.userId=$uid,
                      t.claimKey=$claimKey,
                      t.from=$from,
                      t.to=$to,
                      t.beforeConf=$beforeConf,
                      t.afterConf=$afterConf,
                      t.direction=$direction,
                      t.delta=$delta,
                      t.at=datetime()
                  MERGE (u)-[:HAS_TRANSITION]->(t)
                  MERGE (t)-[:OF_CLAIM]->(c)
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "sid", c.subjectId(),
                    "pred", c.predicate().name(),
                    "oid", c.objectId(),
                    "q", c.quantifier().name(),
                    "pol", c.polarity(),
                    "from", from,
                    "to", to,
                    "beforeConf", delta.beforeConfidence(),
                    "afterConf", delta.afterConfidence(),
                    "direction", delta.direction(),
                    "delta", delta
            )).consume());
        }
    }


}
