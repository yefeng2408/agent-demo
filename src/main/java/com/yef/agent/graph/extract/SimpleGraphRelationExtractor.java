package com.yef.agent.graph.extract;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.eum.InteractionType;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import org.springframework.stereotype.Component;

@Component
public class SimpleGraphRelationExtractor {

    public ExtractedRelation extract(
            String msg,
            String userId,
            InteractionType interactionType
    ) {
        // ASK：不抽取语义
        if (interactionType == InteractionType.ASK) {
            return null;
        }

        // demo 阶段：只处理 Tesla + OWNS
        if (!msg.contains("特斯拉")) {
            return null;
        }

        boolean polarity;
        if (msg.contains("没有") || msg.contains("不拥有")) {
            polarity = false;
        } else if (msg.contains("拥有")) {
            polarity = true;
        } else {
            return null;
        }

        String subjectId = "USER:" + userId;

        return ExtractedRelation.forUserStatement(
                subjectId,
                PredicateType.OWNS,
                "BRAND:Tesla",
                Quantifier.ONE,
                polarity
        );
    }
}