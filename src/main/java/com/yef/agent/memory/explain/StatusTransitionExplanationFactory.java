package com.yef.agent.memory.explain;

public class StatusTransitionExplanationFactory {

    public static ExplanationItem explain(StatusTransitionSnapshot t) {

        String text = String.format(
                "该结论基于一次认知状态变化：由「%s」转变为「%s」。",
                t.from(),
                t.to()
        );

        return ExplanationItem.user(
                ExplanationType.STATUS_TRANSITION,
                text,
                1,
                "status_transition"
        );
    }
}