package com.yef.agent.graph.extract;

import com.yef.agent.graph.ExtractedRelation;
import java.util.List;

@Deprecated
public interface GraphRelationExtractor {

    List<ExtractedRelation> extract(String userId, String userMessage);

}