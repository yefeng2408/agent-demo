package com.yef.agent.memory.explain;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.ClaimGeneration;
import com.yef.agent.memory.explain.biz.EpistemicExplanationRepository;
import com.yef.agent.memory.explain.biz.ExplainableAnswerBuilder;
import com.yef.agent.memory.decision.*;
import com.yef.agent.memory.decision.biz.DominantDecision;
import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeTone;
import com.yef.agent.memory.narrative.service.NarrativeService;
import com.yef.agent.memory.vo.DominantClaimVO;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultExplainableAnswerBuilder
        implements ExplainableAnswerBuilder {

    private static final ExplanationRanker.Config USER_CFG
            = ExplanationRanker.Config.userDefault();

    private final EpistemicExplanationRepository explanationRepo;
    private final ExplanationRanker ranker;
    private final NarrativeService narrativeService;
    private final EpistemicExplanationRepository epistemicExplanationRepository;
    private final KeyCodec keyCodec;

    public DefaultExplainableAnswerBuilder(@Autowired EpistemicExplanationRepository explanationRepo,
                                           @Autowired ExplanationRanker ranker,
                                           @Autowired NarrativeService narrativeService,
                                           @Autowired EpistemicExplanationRepository epistemicExplanationRepository,
                                           @Autowired KeyCodec keyCodec
    ) {
        this.explanationRepo = explanationRepo;
        this.ranker = ranker;
        this.narrativeService = narrativeService;
        this.epistemicExplanationRepository = epistemicExplanationRepository;
        this.keyCodec = keyCodec;
    }

    @Override
    public AnswerResult buildOwnsAnswer(
            String userId,
            String objectName,
            DominantDecision decision,
            List<ClaimEvidence> candidates) {

        // 1️⃣ citations
        List<Citation> citations = candidates.stream()
                .map(this::toCitation)
                .toList();

        // 2️⃣ explanations
        List<ExplanationItem> raw = explanationRepo.explainAll(decision.reason());

        // 3️⃣ override history（只在有 dominant 的情况下）
        ClaimEvidence dominantEvidence =
                decision.decidedClaim().orElse(null);

        if (dominantEvidence != null) {
            raw.addAll(
                    epistemicExplanationRepository
                            .explainOverrideHistory(keyCodec.buildEvidenceKey(dominantEvidence))
            );
        }

        // 4️⃣ normalize & build
        List<ExplanationItem> explanations = ranker.normalize(raw, USER_CFG);
        // 3️⃣ 最终裁决
        if (decision.isFinal()) {

            ClaimEvidence c = decision.decidedClaim().orElseThrow();

            String answer =
                    (decision instanceof DeniedDecision)
                            ? "我目前不拥有" + objectName + "。"
                            : "我目前拥有" + objectName + "。";

            return AnswerResult.ok(
                    answer,
                    toRelation(c),
                    citations,
                    explanations,
                    decision

            );
        }

        // 4️⃣ 不确定态
        return new AnswerResult(
                false,
                "关于我是否拥有" + objectName + "，目前尚无法形成稳定结论。",
                null,
                citations,
                explanations,
                decision
        );
    }

    @Override
    public AnswerResult build(String userId, ExtractedRelation r, DominantClaimVO dom) {

        // ① 事实核心（不带语气）
        String core = renderFact(dom.claim());

        // ② 叙事评分
        DominantNarrativeScore score = narrativeService.buildScore(dom);

        NarrativeTone tone = narrativeService.decideTone(score);
        // ③ 包装语气
        String finalAnswer = narrativeService.renderWithTone(tone, core);

        return AnswerResult.ok(
                finalAnswer,
                List.of(toCitation(dom.claim())),
                tone,
                score
        );
    }


    private ExtractedRelation toRelation(ClaimEvidence c) {
        return new ExtractedRelation(
                c.subjectId(),
                c.predicate(),
                c.objectId(),
                c.quantifier(),
                c.polarity(),
                c.confidence(),
                c.source(),
                ClaimGeneration.V3
        );
    }


    private Citation toCitation(ClaimEvidence claimEvidence) {
        return new Citation(
                claimEvidence.predicate().name(),
                claimEvidence.subjectId(),
                claimEvidence.objectId(),
                claimEvidence.quantifier().name(),
                claimEvidence.polarity(),
                claimEvidence.confidence(),
                claimEvidence.source().name(),
                claimEvidence.batch(),
                claimEvidence.updatedAt()
        );
    }


}

