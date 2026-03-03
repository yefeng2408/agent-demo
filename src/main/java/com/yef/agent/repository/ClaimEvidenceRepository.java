package com.yef.agent.repository;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.SlotBeliefState;
import com.yef.agent.memory.pipeline.TransitionReason;
import com.yef.agent.memory.vo.DominantSnapshot;
import com.yef.agent.memory.vo.OverriddenEdgeVO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClaimEvidenceRepository {

    List<ClaimEvidence> loadActiveBySlot(String userId, String slotKey);

    int countRecentDominantSwitches(String userId, String slotKey, long switchWindowSec);

    void touchDominantMeta(String userId, String slotKey, String s);

    void appendDominantSwitchEvent(String userId, String slotKey, String curKey, String s, TransitionReason reason);

    void updateMomentum(String claimKey, double newMomentum, Instant now);

    Optional<ClaimEvidence> loadDominantClaim(String userId, String slotKey);

    Optional<ClaimEvidence> findBestOpposite(String userId, String slotKey, boolean newPolarity);

    List<ClaimEvidence> loadAllBySlot(String userId, String slotKey);


    ClaimEvidence loadEvidenceClaimByClaimKey(String userId, String claimKey);


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


    void clearDominant(String userId, String claimSlot);


        void writeDominant(
            String userId,
            String claimKey,
            String claimEvidenceId,
            SlotBeliefState beliefState,
            String reason);


    void writeOverriddenBy(String userId,
                           String fromClaimKey,
                           String toClaimKey,
                           String reason,
                           double intentConfidence);

    Optional<DominantSnapshot> loadDominant(String userId, String slotKey);



    List<OverriddenEdgeVO> loadOverrideHistory(String claimEvidenceId);


    boolean hasDominant(String userId, String claimSlot);


    Optional<Instant> loadLastSwitchAt(String userId, String slotKey);

    void initOpposeClaim(String userIdm, ExtractedRelation r);
}
