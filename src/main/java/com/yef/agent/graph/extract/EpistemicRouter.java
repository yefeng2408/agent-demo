/*
package com.yef.agent.graph.extract;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.GraphAnswerer;
import com.yef.agent.graph.eum.InteractionType;
import com.yef.agent.service.ClaimConfidenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class EpistemicRouter {

    private final GraphRelationExtractor relationExtractor;
    private final GraphAnswerer graphAnswerer;
    private final ClaimConfidenceService claimConfidenceService;

    EpistemicRouter(GraphRelationExtractor relationExtractor,
                    GraphAnswerer graphAnswerer,
                    ClaimConfidenceService claimConfidenceService) {
        this.relationExtractor = relationExtractor;
        this.graphAnswerer = graphAnswerer;
        this.claimConfidenceService = claimConfidenceService;
    }

    public String handleWrite(String userId, String msg, InteractionType type) {
        // ASSERT / CHALLENGE 才会进来
        List<ExtractedRelation> relations = relationExtractor.extract(userId, msg);
        if (relations == null || relations.isEmpty()) {
            return "未抽取到可写入的关系";
        }

        ExtractedRelation r = relations.stream()
                .max(Comparator.comparingDouble(ExtractedRelation::confidence))
                .orElse(relations.get(0));

        // 注意：这里不能调用 graphAnswerer.answer(msg)
        AnswerResult dominantView = graphAnswerer.answerByRelation(userId, r);

        // ✅ applyAnswer 只允许在 ASSERT/CHALLENGE
        claimConfidenceService.applyAnswer(userId, dominantView, r);

        return "已写入/更新: " + r.toReadableText();
    }
}
}
*/
