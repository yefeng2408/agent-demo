package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.repository.impl.ClaimEvidenceRepositoryImpl;
import com.yef.agent.component.DeltaComputer;
import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.SemanticRelation;
import com.yef.agent.memory.ClaimDelta;
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
    private final ClaimEvidenceRepositoryImpl evidenceRepo;
    private final DeltaComputer deltaComputer;
    private final KeyCodec keyCodec;

    public OpposeDeltaStrategy(
            Driver driver,
            ClaimEvidenceRepositoryImpl evidenceRepo,
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
        //return ctx.opposite() != null;
        return ctx.semanticRelation() == SemanticRelation.OPPOSE;
    }

    @Override
    public List<ClaimDelta> apply(EpistemicContext ctx) {
        /*// 下一步我们一步一步写
        return doApplyOppose(ctx);*/

        String userId = ctx.userId();
        Citation dominant = ctx.dominant();
        ExtractedRelation opposite = ctx.opposite();

        // 1️⃣ 读取 before
        ClaimEvidence domBefore = evidenceRepo.loadClaimEvidence(
                userId,
                dominant.subjectId(),
                PredicateType.valueOf(dominant.predicate()),
                dominant.objectId(),
                Quantifier.valueOf(dominant.quantifier()),
                dominant.polarity()
        );

        ClaimEvidence oppBefore = evidenceRepo.loadClaimEvidence(
                userId,
                opposite.subjectId(),
                opposite.predicateType(),
                opposite.objectId(),
                opposite.quantifier(),
                opposite.polarity()
        );

        // 2️⃣ 计算 step
        double domStep = deltaComputer.computeStep(domBefore.confidence());
        double oppStep = deltaComputer.computeStep(oppBefore.confidence());

        // 3️⃣ 计算 after（一次！）
        double domAfter = clamp(domBefore.confidence() - domStep);
        double oppAfter = clamp(oppBefore.confidence() + oppStep);

        // 4️⃣ 只返回 delta，不写库
        return List.of(
                ClaimDelta.confidenceOnly(
                        Citation.from(domBefore),
                        domBefore.confidence(),
                        domAfter
                ),
                ClaimDelta.confidenceOnly(
                        Citation.from(oppBefore),
                        oppBefore.confidence(),
                        oppAfter
                )
        );
    }

    @Override
    public double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
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
