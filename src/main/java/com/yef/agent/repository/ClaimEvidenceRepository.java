package com.yef.agent.repository;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.narrative.OverrideEvent;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.memory.vo.OverriddenEdgeVO;
import java.util.List;
import java.util.Optional;

public interface ClaimEvidenceRepository {

    ClaimEvidence loadByKey(String userId, String claimKey);


    ClaimEvidence loadClaimEvidence(
            String userId,
            String subjectId,
            PredicateType predicate,
            String objectId,
            Quantifier quantifier,
            boolean polarity);


    void updateEpistemicStatus(String claimKey, EpistemicStatus epistemicStatus);


    ClaimEvidence updateConfidence(String claimKey, double confidence);


    List<ClaimEvidence> loadAllByClaimKey(String userId, String claimKey);


    void clearDominant(String userId, String claimKey);


    void writeDominant(
            String userId,
            String claimKey,
            String claimEvidenceId,
            double supportConfidenceAt,
            String reason);


    void writeOverriddenBy(
            String oldClaimEvidenceId,
            String newClaimEvidenceId,
            String reason);


    Optional<ClaimEvidence> loadDominant(String userId, String claimKey);

    Optional<DominantClaimVO> loadDominant2(String userId, String claimKey);


    List<OverriddenEdgeVO> loadOverrideHistory(String claimEvidenceId);


    List<OverrideEvent> loadOverrideHistory2(String claimEvidenceId);

}
