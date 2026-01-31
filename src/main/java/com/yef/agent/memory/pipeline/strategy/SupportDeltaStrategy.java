package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.component.ClaimEvidenceRepository;
import com.yef.agent.component.DeltaComputer;
import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.DeltaDirection;
import com.yef.agent.memory.pipeline.EpistemicContext;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import static org.neo4j.driver.Values.parameters;

@Component
public class SupportDeltaStrategy implements DeltaStrategy {

    private final Driver driver;
    private final ClaimEvidenceRepository evidenceRepo; // 已有 loadClaimEvidence 的地方，建议抽一下
    private final DeltaComputer deltaComputer;          // computeDelta
    private final KeyCodec keyCodec;                    // buildEvidenceKey

    public SupportDeltaStrategy(
            Driver driver,
            ClaimEvidenceRepository evidenceRepo,
            DeltaComputer deltaComputer,
            KeyCodec keyCodec) {
        this.driver = driver;
        this.evidenceRepo = evidenceRepo;
        this.deltaComputer = deltaComputer;
        this.keyCodec = keyCodec;
    }

    @Override
    public boolean supports(EpistemicContext ctx) {
        // 最稳：以“有没有 opposite”为判定也行，但建议按 answerResult / intent
        // 这里先按 opposite == null 认为 support
        return ctx.opposite() == null;
    }

    @Override
    public List<ClaimDelta> apply(EpistemicContext ctx) {
        Citation dominant = ctx.dominant();
        double delta = deltaComputer.computeStep(dominant.confidence());
        double after = clamp(dominant.confidence() + delta);
        ClaimDelta d = applySupportDelta(ctx.userId(), dominant, after);
        return List.of(d);
    }

    private ClaimDelta applySupportDelta(String userId, Citation dominant, double delta) {
        // 1) 读取 before
        ClaimEvidence before = evidenceRepo.loadClaimEvidence(
                userId,
                dominant.subjectId(),
                PredicateType.valueOf(dominant.predicate()),
                dominant.objectId(),
                Quantifier.valueOf(dominant.quantifier()),
                dominant.polarity()
        );

        // 2) 计算 after（clamp 0..1）
        double after = clamp(before.confidence() + delta);

        // 3) 写回 Neo4j（只更新 confidence + updatedAt）
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
          subjectId:$sid,
          predicate:$pred,
          objectId:$oid,
          quantifier:$q,
          polarity:$pol
        })
        SET c.confidence = $conf,
            c.updatedAt = datetime()
        RETURN c.updatedAt AS updatedAt
        """;

        Instant updatedAt;
        try (Session session = driver.session()) {
            var rec = session.executeWrite(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "sid", dominant.subjectId(),
                            "pred", dominant.predicate(),
                            "oid", dominant.objectId(),
                            "q", dominant.quantifier(),
                            "pol", dominant.polarity(),
                            "conf", after
                    )).single()
            );
            updatedAt = rec.get("updatedAt").asZonedDateTime().toInstant();
        }
        // 4) 组装更新后快照（用于 key）
        ClaimEvidence afterEvidence = new ClaimEvidence(
                before.subjectId(),
                before.predicate(),
                before.objectId(),
                before.quantifier(),
                before.polarity(),
                before.epistemicStatus(),
                after,
                before.source(),
                before.batch(),
                updatedAt,
                before.priority()
        );
        // 5) ClaimDelta（关键：beforeConfidence 用 before.confidence()，afterConfidence 用 after）
        return new ClaimDelta(
                keyCodec.buildEvidenceKey(afterEvidence),
                before.confidence(),
                after,
                delta,
                DeltaDirection.UP
        );
    }


    public double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }


}