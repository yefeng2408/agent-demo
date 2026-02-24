package com.yef.agent.memory.intent;

public record IntentOutput(
        String intent,
        double confidence
) {}