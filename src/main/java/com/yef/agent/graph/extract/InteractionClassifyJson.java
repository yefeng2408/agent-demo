package com.yef.agent.graph.extract;

import com.yef.agent.graph.eum.InteractionType;

public record InteractionClassifyJson(
        InteractionType interactionType,
        double confidence,
        String rationale
) {



}
