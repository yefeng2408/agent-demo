package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.component.ClaimEvidenceRepository;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.SupportLevel;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import java.util.List;
import static org.neo4j.driver.Values.parameters;

/**
 * 状态迁移
 */
@Component
public class StatusTransitionStage {

    private final Driver driver;
    private final ClaimEvidenceRepository claimEvidenceRepository;

    public StatusTransitionStage(Driver driver,
                                 ClaimEvidenceRepository claimEvidenceRepository) {
        this.driver = driver;
        this.claimEvidenceRepository = claimEvidenceRepository;
    }

    public void apply(String userId, List<ClaimDelta> deltas) {
        ClaimDelta primary = deltas.get(0); // dominant

        ClaimEvidence evidence = claimEvidenceRepository.loadByKey(userId, primary.claimKey());

        maybeTransitionStatus(userId, evidence);
    }



    public void maybeTransitionStatus(String userId, ClaimEvidence c) {
        EpistemicStatus current = c.epistemicStatus() == null
                ? EpistemicStatus.UNKNOWN
                : c.epistemicStatus();

        EpistemicStatus next = deriveStatus(c);

        if (current == next) {
            return; // 🚫 没变化，不记录
        }

        writeStatusTransition(userId, c, current.name(), next.name());
    }


    private EpistemicStatus deriveStatus(ClaimEvidence c) {
        SupportLevel level = SupportLevelResolver.derive(c.confidence());
        return switch (level) {
            case STRONG -> EpistemicStatus.CONFIRMED;
            case WEAK   -> EpistemicStatus.HYPOTHETICAL;
            case NONE   -> EpistemicStatus.UNKNOWN;
        };
    }

    private void writeStatusTransition(
            String userId,
            ClaimEvidence c,
            String from,
            String to) {
        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(cl:Claim {
                  subjectId:$sid,
                  predicate:$pred,
                  objectId:$oid,
                  quantifier:$q,
                  polarity:$pol,
                  legacy:false
                })
                SET cl.epistemicStatus = $to,
                    cl.updatedAt = datetime()
            
                MERGE (fromS:EpistemicStatus {name:$from})
                MERGE (toS:EpistemicStatus {name:$to})
            
                CREATE (t:StatusTransition {
                  id: randomUUID(),
                  at: datetime(),
                  reason: 'threshold-derived',
                  from: $from,
                  to: $to,
                  confidence: cl.confidence,
                  supportCount: cl.supportCount
                })
            
                MERGE (cl)-[:HAS_TRANSITION]->(t)
                MERGE (t)-[:FROM]->(fromS)
                MERGE (t)-[:TO]->(toS)
            
                WITH cl, toS
                OPTIONAL MATCH (cl)-[old:CURRENT_STATUS]->(:EpistemicStatus)
                DELETE old
                MERGE (cl)-[:CURRENT_STATUS]->(toS)
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
                    "to", to
            )).consume());
        }
    }


}