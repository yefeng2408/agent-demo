package com.yef.agent.memory.explain;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 解释治理器：去重、排序、裁剪（控制信息密度）
 */
@Component
public class ExplanationRanker {

    public record Config(
            int maxSections,
            boolean includeDevNotes
    ) {

        public static Config userDefault() {
            return new Config(3, false);
        }

    }

    public List<ExplanationItem> normalize(List<ExplanationItem> raw, Config cfg) {
        if (raw == null || raw.isEmpty()) return List.of();

        // 1) 过滤受众
        List<ExplanationItem> filtered = raw.stream()
                .filter(i -> cfg.includeDevNotes() || i.audience() == Audience.USER)
                .toList();

        // 2) dedupe：同 dedupeKey 只留 priority 更小（更重要）的那条
        Map<String, ExplanationItem> bestByKey = new HashMap<>();
        for (ExplanationItem item : filtered) {
            bestByKey.merge(
                    item.dedupeKey(),
                    item,
                    (a, b) -> a.priority() <= b.priority() ? a : b
            );
        }

        // 3) 排序：priority asc，然后 type，再 text
        List<ExplanationItem> sorted = bestByKey.values().stream()
                .sorted(Comparator
                        .comparingInt(ExplanationItem::priority)
                        .thenComparing(i -> i.type().name())
                        .thenComparing(ExplanationItem::text))
                .collect(Collectors.toList());

        // 4) 裁剪：最多 N 段
        if (sorted.size() <= cfg.maxSections()) return sorted;
        return sorted.subList(0, cfg.maxSections());
    }
}