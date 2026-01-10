package com.yef.agent.advisor;

import java.util.List;

public record FactExtractionResult(
        boolean hasFacts,
        String confidence, // high | low
        List<String> facts
) {}