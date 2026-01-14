package com.yef.agent.graph.answer;

import com.yef.agent.graph.ExtractedRelation;

public interface GraphWriter {
    void writeRelation(ExtractedRelation r);
}