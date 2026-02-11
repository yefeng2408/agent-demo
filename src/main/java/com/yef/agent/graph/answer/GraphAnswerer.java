package com.yef.agent.graph.answer;

import com.yef.agent.graph.ExtractedRelation;

public interface GraphAnswerer {
    /**
     * 根据图中的事实，回答一个自然语言问题
     */
    AnswerResult answer(String userId, String question);

    /**
     * 根据 relation 查 dominant
     * @param userId
     * @param relation
     * @return
     */
    AnswerResult answerByRelation(String userId, ExtractedRelation relation);
}