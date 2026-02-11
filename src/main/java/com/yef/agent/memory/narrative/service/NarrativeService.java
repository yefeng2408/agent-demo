package com.yef.agent.memory.narrative.service;

import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.memory.narrative.DominantNarrativeScore;
import com.yef.agent.memory.narrative.NarrativeCondition;
import com.yef.agent.memory.narrative.NarrativeContext;
import com.yef.agent.memory.narrative.NarrativeTone;
import com.yef.agent.memory.vo.DominantClaimVO;

/**
 * 叙事服务
 */
public interface NarrativeService {

    DominantNarrativeScore buildScore(DominantClaimVO dom);

    NarrativeTone decideTone(DominantNarrativeScore score);

    String renderWithTone(NarrativeTone tone, String coreAnswer);


    NarrativeCondition decideOwnershipTone(NarrativeContext ctx);

    String renderOwnershipAnswer(
            NarrativeTone tone,
            ClaimEvidence dominant
    );
}
