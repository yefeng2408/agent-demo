package com.yef.agent.memory.explain;

import com.yef.agent.memory.explain.biz.EpistemicExplanationRepository;
import com.yef.agent.memory.explain.biz.StatusTransitionExplanationRepository;
import com.yef.agent.memory.decision.DecisionReason;
import com.yef.agent.memory.vo.OverriddenEdgeVO;
import com.yef.agent.repository.ClaimEvidenceRepository;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultEpistemicExplanationRepository implements EpistemicExplanationRepository {

    private final StatusTransitionExplanationRepository transitionRepo;
    private final ClaimEvidenceRepository claimEvidenceRepository;

    public DefaultEpistemicExplanationRepository(StatusTransitionExplanationRepository transitionRepo,
                                                 ClaimEvidenceRepository claimEvidenceRepository) {
        this.transitionRepo = transitionRepo;
        this.claimEvidenceRepository = claimEvidenceRepository;
    }


    public List<ExplanationItem> explainAll(DecisionReason reason) {

        // ① 原有 decision reason
        List<ExplanationItem> items = explainDecision(reason);

        //TODO ② 新增：状态迁移解释（可选）
/*        transitionRepo.findLatestRelevant(null)
                .map(StatusTransitionExplanationFactory::explain)
                .ifPresent(items::add);*/

        return items;
    }

    private List<ExplanationItem> explainDecision(DecisionReason reason) {

        List<ExplanationItem> list = new ArrayList<>();

        switch (reason) {

            // ⭐ Momentum主导
            case MOMENTUM_DOMINANT -> list.add(
                    ExplanationItem.system(
                            ExplanationType.DECISION_REASON,
                            "当前主导结论由长期动量优势决定。",
                            10,
                            "momentum"
                    )
            );

            // ⭐ 无beliefState
            case NO_BELIEFSTATE -> list.add(
                    ExplanationItem.system(
                            ExplanationType.DECISION_REASON,
                            "尚未形成稳定信念，基于当前最高置信度进行暂时判断。",
                            5,
                            "fallback"
                    )
            );

            // ⭐ fallback confidence
            case FALLBACK_CONFIDENCE -> list.add(
                    ExplanationItem.system(
                            ExplanationType.DECISION_REASON,
                            "当前无主导动量，暂以最高置信度作为参考。",
                            3,
                            "fallback-confidence"
                    )
            );

            default -> {
                // ⭐ 向后兼容（防止旧reason）
                list.add(
                        ExplanationItem.system(
                                ExplanationType.DECISION_REASON,
                                "系统正在形成判断。",
                                1,
                                "unknown"
                        )
                );
            }
        }

        return list;
    }

    @Override
    public List<ExplanationItem> explainOverrideHistory(String claimEvidenceId) {
        List<OverriddenEdgeVO> edges =
                claimEvidenceRepository.loadOverrideHistory(claimEvidenceId);

        if (edges.isEmpty()) {
            return List.of();
        }

        OverriddenEdgeVO latest = edges.get(0);

        String text = switch (latest.reason()) {
            case "confidence_overcome" ->
                    "该结论取代了先前的说法，因为新的证据置信度更高。";
            case "recent_update" ->
                    "该结论基于更新后的陈述，覆盖了之前的判断。";
            default ->
                    "该结论是在综合比较多条相互冲突的陈述后得出的。";
        };

        return List.of(
                ExplanationItem.system(
                        ExplanationType.OVERRIDDEN_REASON,
                        text,
                        2,
                        "override:" + claimEvidenceId
                )
        );
    }


}