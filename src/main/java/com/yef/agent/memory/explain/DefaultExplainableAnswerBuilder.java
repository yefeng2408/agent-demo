package com.yef.agent.memory.explain;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.ClaimGeneration;
import com.yef.agent.memory.explain.biz.EpistemicExplanationRepository;
import com.yef.agent.memory.explain.biz.ExplainableAnswerBuilder;
import com.yef.agent.memory.selector.*;
import com.yef.agent.memory.selector.biz.DominantDecision;
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

    public DefaultExplainableAnswerBuilder(@Autowired EpistemicExplanationRepository explanationRepo,
                                           @Autowired ExplanationRanker ranker) {
        this.explanationRepo = explanationRepo;
        this.ranker = ranker;
    }

    @Override
    public AnswerResult buildOwnsAnswer(
            String userId,
            String objectName,
            DominantDecision decision,
            List<ClaimEvidence> candidates) {

        // 1️⃣ citations：完整列出，保证可解释性
        List<Citation> citations =
                candidates.stream()
                        .map(this::toCitation)
                        .toList();

        // 2️⃣ explanations：多段解释
        List<ExplanationItem> raw = explanationRepo.explainAll(decision.reason());
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
                    explanations
            );
        }

        // 4️⃣ 不确定态
        return new AnswerResult(
                false,
                "关于我是否拥有" + objectName + "，目前尚无法形成稳定结论。",
                null,
                citations,
                explanations
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

