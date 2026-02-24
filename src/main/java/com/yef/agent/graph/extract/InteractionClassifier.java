package com.yef.agent.graph.extract;

import com.yef.agent.graph.eum.InteractionType;

/**
 * 	ASK：只读、不可写、不可触发 delta/博弈
 * 	ASSERT：可写入 claim，但默认不触发对立博弈
 * 	CHALLENGE：必须进入对立博弈（若对手存在；若不存在则创建对手槽位）
 */
public interface InteractionClassifier {

    ClassificationResult classify(String userId, String rawText);


    record ClassificationResult(
            InteractionType interactionType,
            double confidence,          // 0~1: 分类把握
            String rationale            // 可选：调试用（不一定存库）
    ) {}
}
