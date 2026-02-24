package com.yef.agent.memory.vo;

public record OverrideEdge(
        String fromKey,
        String toKey,
        String reason,
        double intentConfidence,
        String createdAt
) {}