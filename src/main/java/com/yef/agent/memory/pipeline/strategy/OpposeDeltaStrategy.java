package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.component.ClaimEvidenceRepository;
import com.yef.agent.component.DeltaComputer;
import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.DeltaDirection;
import com.yef.agent.memory.pipeline.EpistemicContext;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import java.util.List;
import static org.neo4j.driver.Values.parameters;

@Slf4j
@Component
public class OpposeDeltaStrategy implements DeltaStrategy {

    private final Driver driver;
    private final ClaimEvidenceRepository evidenceRepo;
    private final DeltaComputer deltaComputer;
    private final KeyCodec keyCodec;

    public OpposeDeltaStrategy(
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
        // 简化版：存在 opposite 即认为是 oppose
        return ctx.opposite() != null;
    }

    @Override
    public List<ClaimDelta> apply(EpistemicContext ctx) {
        // 下一步我们一步一步写
        return doApplyOppose(ctx);
    }

    @Override
    public double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }


    private List<ClaimDelta> doApplyOppose(EpistemicContext ctx) {

        String userId = ctx.userId();
        Citation dominant = ctx.dominant();
        ExtractedRelation opposite = ctx.opposite();

        // 1️⃣ 读取 dominant（被反驳）
        ClaimEvidence domBefore = evidenceRepo.loadClaimEvidence(
                userId,
                dominant.subjectId(),
                PredicateType.valueOf(dominant.predicate()),
                dominant.objectId(),
                Quantifier.valueOf(dominant.quantifier()),
                dominant.polarity()
        );

        // 2️⃣ 读取 opposite（被加强）
        ClaimEvidence oppBefore = evidenceRepo.loadClaimEvidence(
                userId,
                opposite.subjectId(),
                opposite.predicateType(),
                opposite.objectId(),
                opposite.quantifier(),
                opposite.polarity()
        );
        log.info("---> domBefore={}", domBefore);
        log.info("---> oppBefore={}", oppBefore);
        // 3️⃣ 计算 delta（方向很重要）
        double stepDominant = deltaComputer.computeStep(domBefore.confidence());
        double stepOppose = deltaComputer.computeStep(oppBefore.confidence());

        double dominantAfter = clamp(domBefore.confidence() - stepDominant);
        double opposeAfter = clamp(oppBefore.confidence() + stepOppose);

        // 4️⃣ 写回 Neo4j（一次事务，原子性）
        writeOpposeUpdate(userId, dominant, dominantAfter, opposite, opposeAfter);

        // 5️⃣ 产出 ClaimDelta（这是 pipeline 的“产品”）
        ClaimDelta dominantDelta = new ClaimDelta(
                keyCodec.buildEvidenceKey(domBefore),
                domBefore.confidence(),
                dominantAfter,
                stepDominant,
                DeltaDirection.DOWN);

        ClaimDelta oppositeDelta = new ClaimDelta(
                keyCodec.buildEvidenceKey(oppBefore),
                oppBefore.confidence(),
                opposeAfter,
                stepOppose,
                DeltaDirection.UP);

        return List.of(dominantDelta, oppositeDelta);
    }


    private void writeOpposeUpdate(
            String userId,
            Citation dominant, double domAfter,
            ExtractedRelation opposite, double oppAfter) {
        String cypher = """
                        MATCH (u:User {id:$uid})
                        MATCH (u)-[:ASSERTS]->(d:Claim {
                            subjectId:$dsid, predicate:$dpred, objectId:$doid,
                            quantifier:$dq, polarity:$dpol
                        })
                        MATCH (u)-[:ASSERTS]->(o:Claim {
                            subjectId:$osid, predicate:$opred, objectId:$ooid,
                            quantifier:$oq, polarity:$opol
                        })
                        SET d.confidence = $dconf, d.updatedAt = datetime(),
                            o.confidence = $oconf, o.updatedAt = datetime()
                        """;

        try (Session session = driver.session()) {
            session.executeWrite(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,

                            "dsid", dominant.subjectId(),
                            "dpred", dominant.predicate(),
                            "doid", dominant.objectId(),
                            "dq", dominant.quantifier(),
                            "dpol", dominant.polarity(),
                            "dconf", domAfter,

                            "osid", opposite.subjectId(),
                            "opred", opposite.predicateType().name(),
                            "ooid", opposite.objectId(),
                            "oq", opposite.quantifier().name(),
                            "opol", opposite.polarity(),
                            "oconf", oppAfter
                    )).consume()
            );
        }
    }




}
