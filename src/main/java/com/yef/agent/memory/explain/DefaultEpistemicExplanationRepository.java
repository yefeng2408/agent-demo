package com.yef.agent.memory.explain;

import com.yef.agent.memory.explain.biz.EpistemicExplanationRepository;
import com.yef.agent.memory.explain.biz.StatusTransitionExplanationRepository;
import com.yef.agent.memory.selector.DecisionReason;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultEpistemicExplanationRepository implements EpistemicExplanationRepository {

    private StatusTransitionExplanationRepository transitionRepo;

    public DefaultEpistemicExplanationRepository(StatusTransitionExplanationRepository transitionRepo) {
        this.transitionRepo = transitionRepo;
    }


    @Override
    public List<ExplanationItem> explainAll(DecisionReason reason) {

        // ① 原有 decision reason
        List<ExplanationItem> items = explainDecision(reason);

        // ② 新增：状态迁移解释（可选）
        transitionRepo.findLatestRelevant(null)
                .map(StatusTransitionExplanationFactory::explain)
                .ifPresent(items::add);

        return items;
    }

    private List<ExplanationItem> explainDecision(DecisionReason reason) {
        List<ExplanationItem> list = new ArrayList<>();
        switch (reason) {

            case CONFIRMED_SINGLE -> {
                list.add(ExplanationItem.user(
                        ExplanationType.DECISION_REASON,
                        "该结论基于用户明确且一致的陈述。",
                        0,
                        "decision"
                ));
            }

            case DENIED_SINGLE -> {
                list.add(ExplanationItem.user(
                        ExplanationType.DECISION_REASON,
                        "用户明确否认了该事实，因此当前认为该结论不成立。",
                        0,
                        "decision"
                ));
            }

            case MULTIPLE_CONFIRMED -> {
                list.add(ExplanationItem.user(
                        ExplanationType.CONFLICT_NOTICE,
                        "存在多条相互冲突但都被确认的陈述。",
                        1,
                        "conflict"
                ));
                list.add(ExplanationItem.user(
                        ExplanationType.FOLLOW_UP_SUGGESTION,
                        "你可以说明最近一次状态变化发生在什么时候。",
                        3,
                        "followup"
                ));
            }

            case HYPOTHETICAL_CONFLICT -> {
                list.add(ExplanationItem.user(
                        ExplanationType.CONFLICT_NOTICE,
                        "当前存在多条假设性或未确认的说法。",
                        1,
                        "conflict"
                ));
                list.add(ExplanationItem.user(
                        ExplanationType.CONFIDENCE_NOTICE,
                        "这些说法尚不足以形成稳定结论。",
                        2,
                        "confidence"
                ));
            }

            case LOW_CONFIDENCE_FALLBACK -> {
                list.add(ExplanationItem.user(
                        ExplanationType.CONFIDENCE_NOTICE,
                        "当前结论基于相对较高置信度的候选推断。",
                        2,
                        "confidence"
                ));
            }

            case NO_CLAIM -> {
                list.add(ExplanationItem.user(
                        ExplanationType.DECISION_REASON,
                        "系统尚未记录与该问题相关的有效陈述。",
                        0,
                        "decision"
                ));
            }

            default -> {
                list.add(ExplanationItem.user(
                        ExplanationType.DECISION_REASON,
                        "当前无法形成稳定结论。",
                        0,
                        "decision"
                ));
            }
        }
        return list;
    }


}