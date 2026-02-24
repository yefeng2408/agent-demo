package com.yef.agent.memory.vo;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.time.Duration;
import java.time.Instant;


/**
 * DominantClaimVO
 *
 * ===== v4 认知系统核心视图对象 =====
 *
 * 作用：
 *   将 Neo4j 图中的 dominant 状态，
 *   转换为 Narrative / Answer 层可直接使用的数据模型。
 *
 * 数据来源：
 *
 *   ClaimEvidence            -> claim 节点本体
 *   DOMINANT 关系 (slot->c)  -> dominantSince / supportConfidenceAt
 *   Service 运行时计算       -> effectiveConfidence / recentlyChallenged
 *
 * 字段说明：
 *
 * claimKey
 *   - ClaimSlot 的唯一标识
 *
 * claim
 *   - 当前 dominant 的 ClaimEvidence 节点
 *
 * baseConfidence
 *   - claim.confidence()
 *   - 表示 claim 自身置信度（未衰减）
 *
 * effectiveConfidence
 *   - supportConfidenceAt * 时间衰减
 *   - Narrative 判断语气强弱的核心指标
 *
 * dominantSince
 *   - 该 claim 成为 dominant 的时间
 *
 * age
 *   - dominant 持续时长
 *
 * status
 *   - claim.epistemicStatus
 *
 * recentlyChallenged
 *   - 运行时计算值，不存图
 *   - 表示最近是否发生认知波动
 *
 * 设计理念：
 *
 *   DominantClaimVO 不是图节点，
 *   而是 “Narrative 视角下的 dominant 投影”。
 */
public class DominantClaimVO {

    // ===== 身份 =====
    private final String claimKey;
    private final ClaimEvidence claim;

    // ===== 立场强度 =====
    private final double baseConfidence;        // challenger.confidence()
    private final double effectiveConfidence;   // 衰减后的

    // ===== 时间 =====
    private final Instant dominantSince;
    private final Duration age;

    // ===== 状态 =====
    private final EpistemicStatus status;
    //recentlyChallenged是计算得到的，不是写入图谱中的
    private final boolean recentlyChallenged;

    public DominantClaimVO(
            String claimKey,
            ClaimEvidence claim,
            double baseConfidence,
            double effectiveConfidence,
            Instant dominantSince,
            Duration age,
            EpistemicStatus status,
            boolean recentlyChallenged
    ) {
        this.claimKey = claimKey;
        this.claim = claim;
        this.baseConfidence = baseConfidence;
        this.effectiveConfidence = effectiveConfidence;
        this.dominantSince = dominantSince;
        this.age = age;
        this.status = status;
        this.recentlyChallenged = recentlyChallenged;
    }

    public static DominantClaimVO fromRecord(Record r, KeyCodec keyCodec) {

        // ========= 1️⃣ 解析 ClaimEvidence =========
        // 你这里 RETURN c，所以 c 是一个 Node
        Node cNode = r.get("c").asNode();

        ClaimEvidence claim = ClaimEvidence.fromNode(cNode);

        // ========= 2️⃣ 解析 DOMINANT 边属性 =========
        Instant dominantSince =
                r.get("since").isNull()
                        ? Instant.now()
                        : r.get("since").asZonedDateTime().toInstant();

        double supportConfidenceAt =
                r.get("sca").isNull()
                        ? claim.confidence()
                        : r.get("sca").asDouble();

        // ========= 3️⃣ 计算 age =========
        Duration age = Duration.between(dominantSince, Instant.now());

        // ========= 4️⃣ effectiveConfidence (v4 设计) =========
        double decay = Math.exp(-age.toHours() / 72.0); // 3天半衰
        double effective = supportConfidenceAt * decay;

        // ========= 5️⃣ claimKey =========
        String claimKey = keyCodec.buildSlotKey(
                claim.subjectId(),
                claim.predicate(),
                claim.objectId(),
                claim.quantifier()
        );

        // ========= 6️⃣ recentlyChallenged =========
        boolean recentlyChallenged =
                claim.lastStatusChangedAt() != null &&
                        Duration.between(
                                claim.lastStatusChangedAt(),
                                Instant.now()
                        ).toMinutes() < 30;

        // ========= 7️⃣ 构建 VO =========
        return new DominantClaimVO(
                claimKey,
                claim,
                claim.confidence(),   // baseConfidence
                effective,
                dominantSince,
                age,
                claim.epistemicStatus(),
                recentlyChallenged
        );
    }
    // ===== getters（只读）=====
    public String claimKey() { return claimKey; }
    public ClaimEvidence claim() { return claim; }

    public double baseConfidence() { return baseConfidence; }
    public double effectiveConfidence() { return effectiveConfidence; }

    public Instant dominantSince() { return dominantSince; }
    public Duration age() { return age; }

    public EpistemicStatus status() { return status; }
    public boolean recentlyChallenged() { return recentlyChallenged; }

}