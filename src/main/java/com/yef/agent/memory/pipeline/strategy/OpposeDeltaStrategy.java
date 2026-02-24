package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.memory.TransitionRef;
import com.yef.agent.memory.intent.EpistemicIntent;
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
import org.springframework.stereotype.Component;

import java.util.List;

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
            KeyCodec keyCodec, KeyCodec keyCodec1) {
        this.driver = driver;
        this.evidenceRepo = evidenceRepo;
        this.deltaComputer = deltaComputer;
        this.keyCodec = keyCodec1;
    }

    @Override
    public boolean supports(EpistemicContext ctx) {
        // 简化版：存在 opposite 即认为是 oppose
        //return ctx.opposite() != null;
        log.info("supports? {}", ctx);
        return ctx.semanticRelation() == SemanticRelation.OPPOSE;
    }

    @Override
    public List<ClaimDelta> apply(EpistemicContext ctx) {

        String userId = ctx.userId();
        Citation dominant = ctx.dominant();
        ExtractedRelation opposite = ctx.opposite();

        // ========= 1️⃣ load before =========
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

        // ========= 2️⃣ compute step =========
        double domStep = deltaComputer.computeStep(domBefore.confidence());
        double oppStep = deltaComputer.computeStep(oppBefore.confidence());

        // ========= 3️⃣ compute after =========
        double domAfter = clamp(domBefore.confidence() - domStep);
        //double oppAfter = clamp(oppBefore.confidence() + oppStep);
        double weight = intentWeight(ctx.intentResult().intent());
        double oppAfter = deltaComputer.computeStep(oppBefore.confidence()) * (1.30 + weight);

        // ========= 4️⃣ keys =========
        String domKey = keyCodec.buildEvidenceKey(domBefore);
        String oppKey = keyCodec.buildEvidenceKey(oppBefore);

        // ⭐ override target
        // oppose 上升时，override 目标是 dominant
        String overrideTargetForOpp = domKey;

        // dominant 下降一般没有 override target
        String overrideTargetForDom = null;

        // ========= 5️⃣ build delta =========
        ClaimDelta domDelta = ClaimDelta.confidenceOnly(
                domKey,
                domBefore.confidence(),
                domAfter,
                EpistemicIntent.ASSERT_STRONG,
                0.0,
                overrideTargetForDom
        );

        ClaimDelta oppDelta = ClaimDelta.confidenceOnly(
                oppKey,
                oppBefore.confidence(),
                oppAfter,
                ctx.intentResult().intent(),   // ⭐ oppose = challenge dominant
                ctx.intentResult().confidence(),  // 可以后面改成 LLM 输出
                overrideTargetForOpp
        );

        return List.of(domDelta, oppDelta);
    }

    @Override
    public double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }


    public double intentWeight(EpistemicIntent intent) {

        return switch (intent) {

            case SELF_CORRECTION -> 1.8;   // 强 override 力

            case ASSERT_STRONG -> 1.3;   // 强 momentum

            case WEAK_ASSERT -> 1.0;

            case HEDGE -> 0.6;

            case DOUBT -> -0.8;

            default -> 0.0;
        };
    }

}
