package com.yef.agent.memory.narrative.repository;

import com.yef.agent.memory.narrative.OverrideEvent;
import com.yef.agent.memory.vo.DominantClaimVO;
import java.util.List;
import java.util.Optional;

public interface NarrativeRepository {

    Optional<DominantClaimVO> loadDominantNarrativeView(String userId, String claimKey);

    List<OverrideEvent> loadOverrideTimeline(String claimEvidenceId);
}
