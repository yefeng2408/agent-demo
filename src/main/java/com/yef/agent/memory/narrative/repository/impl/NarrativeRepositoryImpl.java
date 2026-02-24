package com.yef.agent.memory.narrative.repository.impl;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.memory.narrative.OverrideEvent;
import com.yef.agent.memory.narrative.repository.NarrativeRepository;
import com.yef.agent.memory.vo.DominantClaimVO;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import static org.neo4j.driver.Values.parameters;

@Repository
public class NarrativeRepositoryImpl implements NarrativeRepository {

    private final Driver driver;
    private final KeyCodec keyCodec;

    public NarrativeRepositoryImpl(Driver driver, KeyCodec keyCodec) {
        this.driver = driver;
        this.keyCodec = keyCodec;
    }


    @Override
    public Optional<DominantClaimVO> loadDominantNarrativeView(String userId, String claimKey) {
        String cypher = """
                MATCH (slot:ClaimSlot {key:$claimKey})
                MATCH (slot)-[d:DOMINANT]->(c:ClaimEvidence)
                RETURN c,
                       d.since AS since,
                       d.supportConfidenceAt AS sca,
                       d.reason AS reason
                LIMIT 1
                """;
        try (Session s = driver.session()) {
            return s.executeRead(tx ->
                    tx.run(cypher, parameters(
                                    "claimKey", claimKey
                            ))
                            .list()
                            .stream()
                            .findFirst()
                            .map(r -> DominantClaimVO.fromRecord(r, keyCodec))
            );
        }
    }



    @Override
    public List<OverrideEvent> loadOverrideTimeline(String claimEvidenceId) {
        String cypher = """
                MATCH (old:ClaimEvidence)-[o:OVERRIDDEN_BY]->(dom:ClaimEvidence {id:$cid})
                RETURN old.id AS fromId,
                       dom.id AS toId,
                       o.at AS at,
                       o.reason AS reason
                ORDER BY o.at DESC
                LIMIT 5
                """;
        try (Session s = driver.session()) {
            return s.executeRead(tx ->
                    tx.run(cypher, parameters(
                                    "cid", claimEvidenceId
                            ))
                            .list()
                            .stream()
                            .map(r -> new OverrideEvent(
                                    r.get("fromId").asString(),
                                    r.get("toId").asString(),
                                    r.get("at").asZonedDateTime().toInstant(),
                                    r.get("reason").asString()
                            ))
                            .toList()
            );
        }
    }

}
