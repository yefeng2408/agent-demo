package com.yef.agent.graph.writer;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.Citation;
import java.util.List;

public interface GraphWriter {

    void writeAnswer(
            String userId,
            ExtractedRelation decision,
            List<Citation> citations,
            String answerText
    );

}
