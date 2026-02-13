package com.yef.agent.memory.narrative;

import com.yef.agent.memory.vo.DominantClaimVO;
import java.time.Duration;
import java.util.List;

/**
 *  Decision Context（决策上下文）
 *
 * @param dominant
 * @param overrideHistory
 * @param dominantDuration
 * @param recentlyChallenged
 */
public record NarrativeContext(DominantClaimVO dominant,
                               List<OverrideEvent> overrideHistory,
                               Duration dominantDuration,
                               boolean recentlyChallenged) {

}