package com.yef.agent.graph.answer;

import com.yef.agent.graph.eum.ClaimGeneration;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import org.neo4j.driver.types.Node;

import java.time.Instant;

/**
 * claim证据对象
 */
public record ClaimEvidence(
        String  claimKey,                      //  唯一
        String slotKey,                      // 所属冲突域
        String subjectId,                     //  主体
        PredicateType predicate,                     //  谓词
        String objectId,                    //Canonical Object
        Quantifier quantifier,                   //   ONE
        boolean polarity,                   //true / false
        double confidence,                  //  0<conf<1
        int supportCount,                    //   累计支持次数
        ClaimGeneration generation,                      // V3
        Source source,                         //USER / SELF_HEAL / EVOLUTION
        Instant createdAt,                      //   创建时间
        Instant updatedAt,
        Instant lastSupportedAt              //   最近支持时间
/*        String elementId,   // ← graph identity
        String claimKey,
        String slotKey,
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
        Instant lastMomentumAt*/

) {


    public static ClaimEvidence fromNode(Node node) {
        String claimKey = node.get("claimKey").asString();
        String slotKey = node.get("slotKey").asString();
        // ===== 五元组 =====
        String subjectId = node.get("subjectId").asString();
        PredicateType predicate = PredicateType.valueOf(node.get("predicate").asString());
        String objectId = node.get("objectId").asString();
        Quantifier qualifier = Quantifier.valueOf(node.get("qualifier").asString());
        boolean polarity = node.get("polarity").asBoolean();

        // ===== 置信度 =====
        double confidence = node.get("confidence").asDouble();

        int supportCount = node.get("supportCount").asInt();
        // ===== 来源 =====
        Source source = Source.valueOf(node.get("source").asString());

        String batch = node.get("batch").asString();

        // ===== 版本 =====
        ClaimGeneration generation = ClaimGeneration.valueOf(node.get("generation").asString());

        // ===== 时间 =====
        Instant createAt = node.get("createAt").asZonedDateTime().toInstant();
        Instant updatedAt = node.get("updatedAt").asZonedDateTime().toInstant();

        Instant lastSupportedAt =
                node.get("lastSupportedAt").isNull()
                        ? null
                        : node.get("lastSupportedAt")
                        .asZonedDateTime()
                        .toInstant();

        return new ClaimEvidence(
                claimKey,
                slotKey,
                subjectId,
                predicate,
                objectId,
                qualifier,
                polarity,
                confidence,
                supportCount,
                generation,
                source,
                createAt,
                updatedAt,
                lastSupportedAt
        );
    }


}