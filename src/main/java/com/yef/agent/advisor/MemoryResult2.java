package com.yef.agent.advisor;

// 定义在 advisor 包下，或者直接写在 UserPersonaAdvisor 类的最下方
public record MemoryResult2(
        boolean shouldStore,
        String content,
        String category,
        String confidence // "high" | "low"
) {}
