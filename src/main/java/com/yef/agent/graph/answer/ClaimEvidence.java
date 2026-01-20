package com.yef.agent.graph.answer;

import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import java.time.Instant;

/**
 *  claim证据对象
 */
public record ClaimEvidence(
        //subjectId，（example，"I have a Tesla car." so,the subjectId is equal to the object userId）
        String subjectId,
        //谓语
        PredicateType predicate,
        //宾语
        String objectId,
        //特指 or 泛指某一类
        Quantifier quantifier,
        //对立观点
        boolean polarity,
        //置信度
        double confidence,
        //区分用户请求的内容是：用户陈述｜自我修正｜提问
        Source source,
        //
        String batch,
        Instant updatedAt,
        //认知裁决顺序
        int priority
) {}