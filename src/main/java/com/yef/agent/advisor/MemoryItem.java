package com.yef.agent.advisor;

import com.yef.agent.util.MemoryType;

public record MemoryItem(
        MemoryType type,
        String confidence,
        String text
) {}