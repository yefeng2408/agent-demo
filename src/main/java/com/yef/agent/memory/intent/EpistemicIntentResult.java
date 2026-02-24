package com.yef.agent.memory.intent;

public record EpistemicIntentResult(
        EpistemicIntent intent,
        double confidence
) {}