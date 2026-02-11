package com.yef.agent.memory.vo;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.EpistemicStatus;
import java.time.Duration;
import java.time.Instant;

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