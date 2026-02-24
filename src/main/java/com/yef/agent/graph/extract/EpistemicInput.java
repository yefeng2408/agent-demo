package com.yef.agent.graph.extract;

/**
 * 输入对象
 * @param userId
 * @param rawText
 * @param receivedAtEpochMs
 */
public record EpistemicInput(
        String userId,
        String rawText,
        long receivedAtEpochMs
) {}