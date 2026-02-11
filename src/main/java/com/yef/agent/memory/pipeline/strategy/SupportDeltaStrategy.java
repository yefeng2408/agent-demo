package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.repository.impl.ClaimEvidenceRepositoryImpl;
import com.yef.agent.component.DeltaComputer;
import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.eum.SemanticRelation;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.pipeline.EpistemicContext;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Component;
import java.util.List;


@Component
public class SupportDeltaStrategy implements DeltaStrategy {

    private final Driver driver;
    private final ClaimEvidenceRepositoryImpl evidenceRepo; // 已有 loadClaimEvidence 的地方，建议抽一下
    private final DeltaComputer deltaComputer;          // computeDelta
    private final KeyCodec keyCodec;                    // buildEvidenceKey

    public SupportDeltaStrategy(
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
        // 最稳：以“有没有 opposite”为判定也行，但建议按 answerResult / intent
        // 这里先按 opposite == null 认为 support
        //return ctx.opposite() == null;
        return ctx.semanticRelation() == SemanticRelation.SUPPORT;
    }

    @Override
    public List<ClaimDelta> apply(EpistemicContext ctx) {
        Citation dominant = ctx.dominant();
        if (dominant == null) {
            return List.of();
        }
        double before = dominant.confidence();

        if (ctx.isRepeatedAssertion(ctx,dominant)) {
            double after = clamp(before + deltaComputer.computeStep(before) * 0.2); // 衰减
            return List.of(
                    ClaimDelta.confidenceOnly(dominant, before, after)
            );
        }

        // 1️⃣ 只在这里算 delta（一次）
        double delta = deltaComputer.computeStep(before);

        // 2️⃣ 只在这里算 after（一次）
        double after = clamp(before + delta);

        // 3️⃣ 只返回“描述性 Delta”，不做任何写操作
        return List.of(ClaimDelta.confidenceOnly(dominant, before, after));


    }



    public double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }



}