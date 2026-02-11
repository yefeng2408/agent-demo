package com.yef.agent.memory.narrative;

import com.yef.agent.memory.narrative.service.NarrativeService;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.repository.ClaimEvidenceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class NarrativeContext {

    @Autowired
    private ClaimEvidenceRepository claimEvidenceRepository;

    private final DominantClaimVO dominant;
    private final List<OverrideEvent> overrideHistory;
    private final Duration dominantDuration;
    private final boolean recentlyChallenged;

    public NarrativeContext(DominantClaimVO dominant,
                            List<OverrideEvent> overrideHistory,
                            Duration dominantDuration,
                            boolean recentlyChallenged) {
        this.dominant = dominant;
        this.overrideHistory = overrideHistory;
        this.dominantDuration = dominantDuration;
        this.recentlyChallenged = recentlyChallenged;
    }

    public NarrativeContext buildNarrativeContext(
            String userId,
            String claimKey) {
        Optional<DominantClaimVO> domOpt = claimEvidenceRepository.loadDominant2(userId, claimKey);
        if (domOpt.isEmpty()) {
            return new NarrativeContext(null, List.of(), Duration.ZERO, false);
        }

        DominantClaimVO dom = domOpt.get();

        List<OverrideEvent> history =
                claimEvidenceRepository.loadOverrideHistory2(dom.claimKey());

        Duration duration =
                Duration.between(dom.dominantSince(), Instant.now());

        boolean recentlyChallenged =
                history.stream().anyMatch(e ->
                        Duration.between(e.at(), Instant.now()).toHours() < 24
                );

        return new NarrativeContext(
                dom,
                history,
                duration,
                recentlyChallenged
        );
    }



    public DominantClaimVO getDominant() {
        return dominant;
    }

    public List<OverrideEvent> getOverrideHistory() {
        return overrideHistory;
    }

    public Duration getDominantDuration() {
        return dominantDuration;
    }

    public boolean isRecentlyChallenged() {
        return recentlyChallenged;
    }
}