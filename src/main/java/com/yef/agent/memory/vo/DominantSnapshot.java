package com.yef.agent.memory.vo;

import com.yef.agent.graph.answer.ClaimEvidence;
import java.time.Instant;

/**
 * DominantSnapshot
 *
 * 作用：
 *   表示 Neo4j 中一个 DOMINANT 关系的“读取快照”。
 *
 * 数据来源：
 *
 *   (slot:ClaimSlot)-[r:DOMINANT]->(c:ClaimEvidence)
 *
 * 包含：
 *   - claim               -> dominant 的 ClaimEvidence 节点
 *   - dominantSince       -> r.since，成为 dominant 的时间
 *   - supportConfidenceAt -> r.supportConfidenceAt，当时支持度
 *
 * 设计原因（v4 核心）：
 *
 *   dominant 的“时间”和“支持度”属于关系语义，
 *   而不是 claim 本体属性。
 *
 *   因此读取时必须组合 Node + Relationship。
 */
public record DominantSnapshot(
        ClaimEvidence claim,
        Instant dominantSince,
        double supportConfidenceAt,
        String reason
) {}