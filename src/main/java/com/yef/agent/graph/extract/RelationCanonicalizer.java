package com.yef.agent.graph.extract;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.memory.vo.CanonicalRelation;

import java.util.Optional;

public interface RelationCanonicalizer {
    Optional<CanonicalRelation> canonicalize(ExtractedRelation r, String originalUserMsg);
}
