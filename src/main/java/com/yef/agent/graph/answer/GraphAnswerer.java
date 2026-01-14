package com.yef.agent.graph.answer;

public interface GraphAnswerer {
    /**
     * 根据图中的事实，回答一个自然语言问题
     */
    AnswerResult answer(String userId, String question);
}