package com.yef.agent.memory.selector.biz;

import com.yef.agent.graph.answer.ClaimEvidence;

import java.util.List;

public interface DominantClaimSelector {

    DominantDecision select(List<ClaimEvidence> candidates);

}