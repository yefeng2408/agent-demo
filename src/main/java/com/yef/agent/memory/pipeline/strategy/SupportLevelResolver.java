package com.yef.agent.memory.pipeline.strategy;

import com.yef.agent.memory.SupportLevel;

public class SupportLevelResolver {

    public static SupportLevel derive(double confidence) {
        if (confidence >= 0.85) {
            return SupportLevel.STRONG;
        }
        if (confidence >= 0.4) {
            return SupportLevel.WEAK;
        }
        return SupportLevel.NONE;
    }
}