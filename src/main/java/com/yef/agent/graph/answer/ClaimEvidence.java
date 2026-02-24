package com.yef.agent.graph.answer;

import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.memory.EpistemicStatus;
import org.neo4j.driver.types.Node;

import java.time.Instant;

/**
 * claim证据对象
 */
public record ClaimEvidence(
        String elementId,   // ← graph identity
        //subjectId
        String subjectId,
        //谓语
        PredicateType predicate,
        //宾语
        String objectId,
        //特指 or 泛指某一类
        Quantifier quantifier,
        //对立观点
        boolean polarity,

        EpistemicStatus epistemicStatus,
        //置信度
        double confidence,
        //区分用户请求的内容是：用户陈述｜自我修正｜提问
        Source source,

        String batch,

        Instant updatedAt,
        //epistemicStatus最近一次的更新时间
        Instant lastStatusChangedAt,
        //认知裁决顺序
        int priority,

        double momentum,
        Instant lastMomentumAt

) {


    public static ClaimEvidence fromNode(Node node) {

        // ===== graph identity =====
        String elementId = node.elementId();

        // ===== 五元组 =====
        String subjectId = node.get("subjectId").asString();
        PredicateType predicate = PredicateType.valueOf(node.get("predicate").asString());
        String objectId = node.get("objectId").asString();
        Quantifier qualifier = Quantifier.valueOf(node.get("qualifier").asString());
        boolean polarity = node.get("polarity").asBoolean();

        // ===== 置信度 =====
        double confidence = node.get("confidence").asDouble();

        // ===== 来源 =====
        Source source = Source.valueOf(node.get("source").asString());

        String batch = node.get("batch").asString();

        // ===== 状态 =====
        EpistemicStatus status = EpistemicStatus.valueOf(node.get("epistemicStatus").asString());

        // ===== 时间 =====
        Instant updatedAt = node.get("updatedAt").asZonedDateTime().toInstant();

        Instant lastStatusChangedAt =
                node.get("lastStatusChangedAt").isNull()
                        ? null
                        : node.get("lastStatusChangedAt")
                        .asZonedDateTime()
                        .toInstant();

        int priority = node.get("priority").asInt();

        return new ClaimEvidence(
                elementId,
                subjectId,
                predicate,
                objectId,
                qualifier,
                polarity,
                status,
                confidence,
                source,
                batch,
                updatedAt,
                lastStatusChangedAt,
                priority,
                0.0d,
                null
        );
    }


}