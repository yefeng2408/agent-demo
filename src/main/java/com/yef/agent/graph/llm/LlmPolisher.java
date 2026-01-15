package com.yef.agent.graph.llm;

import com.yef.agent.graph.answer.AnswerResult;

public interface LlmPolisher {
    String explain(AnswerResult answerResult);
}