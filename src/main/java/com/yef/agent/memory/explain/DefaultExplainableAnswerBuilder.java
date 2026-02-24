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
import com.yef.agent.memory.narrative.NarrativeCondition;
import com.yef.agent.memory.narrative.NarrativeTone;
import com.yef.agent.memory.narrative.NarrativeDecision;
import com.yef.agent.memory.narrative.service.NarrativeService;
import com.yef.agent.memory.vo.DominantClaimVO;
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
        ClaimEvidence dominantEvidence = decision.decidedClaim().orElse(null);

        if (dominantEvidence != null) {
            String claimKey = keyCodec.buildEvidenceKey(dominantEvidence);
            raw.addAll(epistemicExplanationRepository.explainOverrideHistory(claimKey));
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
    public AnswerResult build(String userId, ExtractedRelation relation, DominantClaimVO dom) {
        return null;
    }

    /*   @Override
       public AnswerResult build(String userId, ExtractedRelation r, DominantClaimVO dom) {

           // ① 核心事实（ExplainableAnswer 的“无语气事实骨架”）
           String core = renderFact(dom.claim());

           // ② NarrativeDecision（统一决策入口）
           NarrativeDecision decision = narrativeService.decide(userId, dom);

           NarrativeTone tone = decision.tone();
           NarrativeCondition condition = decision.condition();

           // ③ 根据 Decision 组装 Narrative 前缀/后缀
           StringBuilder answer = new StringBuilder();

           // ---------- (A) recently changed cue ----------
           if (decision.includeRecentlyChangedCue()) {
               answer.append("根据最新记录，");
           }

           // ---------- (B) ownership cue ----------
           if (decision.includeOwnershipCue()) {
               answer.append("目前信息显示，");
           }

           // ---------- (C) contested cue ----------
           if (decision.includeContestedCue()) {
               answer.append("这一点仍存在争议，");
           }

           // ---------- (D) uncertainty cue ----------
           if (decision.includeUncertaintyCue()) {
               switch (condition) {
                   case TENTATIVE -> answer.append("可能是这样的：");
                   case CONTESTED -> answer.append("不同记录之间存在冲突：");
                   case RECENTLY_CHANGED -> answer.append("刚发生变化：");
                   default -> {}
               }
           }

           // ④ Tone 渲染核心句（NarrativeTone 层）
           String tonedCore = narrativeService.renderWithTone(tone, core);
           answer.append(tonedCore);

           // ⑤ contested / uncertain 的尾部解释（Explainable 层）
           if (condition == NarrativeCondition.CONTESTED) {

               answer.append("（该结论近期被新的信息挑战）");

           } else if (condition == NarrativeCondition.RECENTLY_CHANGED) {

               answer.append("（该信息刚刚发生更新）");

           } else if (condition == NarrativeCondition.TENTATIVE) {

               answer.append("（当前置信度仍在变化中）");
           }

           String finalAnswer = answer.toString();

           // ⑥ Citation 控制（Decision 决定是否给证据）
           List<Citation> citations =
                   decision.includeCitations()
                           ? List.of(toCitation(dom.claim()))
                           : List.of();

           // ⑦ 返回 ExplainableAnswer
           return AnswerResult.pk(
                   finalAnswer,
                   citations,
                   tone,
                   decision.score()
           );
       }

   */
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


    private String renderFact(ClaimEvidence c) {

        String subject = c.subjectId();
        String predicate = c.predicate().name().toLowerCase();
        String object = c.objectId();

        String base = subject + " " + predicate + " " + object;

        if (!c.polarity()) {
            return "并不存在证据表明 " + base;
        }
        return base;
    }

}

