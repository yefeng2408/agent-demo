package com.yef.agent.graph.answer;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.explain.biz.ExplainableAnswerBuilder;
import com.yef.agent.memory.decision.biz.DominantClaimSelector;
import com.yef.agent.memory.decision.biz.DominantDecision;
import com.yef.agent.memory.narrative.build.NarrativeAnswerBuilder;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.service.DominantService;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import static org.neo4j.driver.Values.parameters;

@Component
public class Neo4jGraphAnswerer implements GraphAnswerer {

    private static Driver driver = null;

    // 你先用最简单的“意图识别”，后面 Step3.2 用 LLM 产 enum 再替换这里
    private static final Pattern Q_NAME = Pattern.compile("你叫啥|你是谁|名字", Pattern.CASE_INSENSITIVE);
    private static final Pattern Q_BORN = Pattern.compile("哪年出生|几岁|生日|出生", Pattern.CASE_INSENSITIVE);
    private static final Pattern Q_ROLE = Pattern.compile("职业|工作|做什么|干什么|岗位|角色", Pattern.CASE_INSENSITIVE);
    private static final Pattern Q_OWNS = Pattern.compile("有车|有没有车|买车|开车|特斯拉|汽车", Pattern.CASE_INSENSITIVE);

    //该数值越小，查询到的claim越多。仲裁阶段的可解释性越强
    private static final double MIN_CONF = 0.2;

    private final KeyCodec keyCodec;
    private final DominantClaimSelector dominantClaimSelector;
    private final ExplainableAnswerBuilder explainableAnswerBuilder;
    private final NarrativeAnswerBuilder narrativeAnswerBuilder;
    private final DominantService dominantService;

    public Neo4jGraphAnswerer(Driver driver,
                              KeyCodec keyCodec,
                              DominantClaimSelector dominantClaimSelector,
                              ExplainableAnswerBuilder explainableAnswerBuilder, NarrativeAnswerBuilder narrativeAnswerBuilder,
                              DominantService dominantService) {
        Neo4jGraphAnswerer.driver = driver;
        this.keyCodec = keyCodec;
        this.dominantClaimSelector = dominantClaimSelector;
        this.explainableAnswerBuilder = explainableAnswerBuilder;
        this.narrativeAnswerBuilder = narrativeAnswerBuilder;
        this.dominantService = dominantService;
    }

    @Override
    public AnswerResult answer(String userId, String question) {
       /* if (question == null || question.isBlank()) return AnswerResult.unanswered();
        // 先用 rule-based 分流（B 版本先上线）
        if (Q_NAME.matcher(question).find()) {
            return answerSingleValue(userId, "NAME", "NAME:", "我的名字是%s。");
        }
        if (Q_BORN.matcher(question).find()) {
            return answerSingleValue(userId, "BORN_YEAR", "YEAR:", "我出生于%s年。");
        }
        if (Q_ROLE.matcher(question).find()) {
            return answerSingleValue(userId, "HAS_ROLE", "ROLE:", "我的职业/角色是：%s。");
        }
        if (Q_OWNS.matcher(question).find()) {
            return answerOwnsCar(userId);
        }
        return AnswerResult.unanswered();*/
        // 临时写死，只测 ASK_OWNS
        return answerOwns(userId,PredicateType.OWNS);
    }


    private AnswerResult answerOwns(String userId,PredicateType predicate) {
        // 1) 召回：OWNS 的所有候选（不丢证据）
        List<ClaimEvidence> candidates = queryClaims(userId, predicate);
        if (candidates == null || candidates.isEmpty()) {
            return AnswerResult.unanswered();
        }
        // ① 认知裁决（只做“判断”）
        DominantDecision decision = dominantClaimSelector.select(candidates);
        // ② 生成可解释回答（只做“表达”）
        AnswerResult result = explainableAnswerBuilder.buildOwnsAnswer(
                        userId,
                        "特斯拉",
                        decision,
                        candidates);
        return result;
    }

    public static List<ClaimEvidence> queryClaims(String userId, PredicateType predicate) {
        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim)
                WHERE 
                    c.predicate = $pred 
                AND c.legacy = false
                AND c.confidence >= $minConf
                WITH c,
                CASE
                  WHEN toUpper(c.quantifier) = 'ANY' AND c.polarity = false THEN 0
                  WHEN toUpper(c.quantifier) = 'ANY' AND c.polarity = true  THEN 1
                  WHEN toUpper(c.quantifier) = 'ONE' AND c.polarity = true  THEN 2
                  WHEN toUpper(c.quantifier) = 'ONE' AND c.polarity = false THEN 3
                  ELSE 9
                END AS pri
                RETURN
                  c.subjectId  AS subjectId,
                  c.predicate  AS predicate,
                  c.objectId   AS objectId,
                  c.quantifier AS quantifier,
                  c.polarity   AS polarity,
                  c.epistemicStatus AS epistemicStatus,
                  c.confidence AS confidence,
                  c.source     AS source,
                  c.batch      AS batch,
                  c.updatedAt  AS updatedAt,
                  c.lastStatusChangedAt  AS lastStatusChangedAt,
                  pri          AS priority
                ORDER BY pri ASC, c.confidence DESC, c.updatedAt DESC
                """;
        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "pred", predicate.name(),
                            "minConf", MIN_CONF
                    )).list(record -> new ClaimEvidence(
                            record.get("elementId").asString(),
                            record.get("subjectId").asString(),
                            PredicateType.valueOf(record.get("predicate").asString()),
                            record.get("objectId").asString(),
                            Quantifier.valueOf(record.get("quantifier").asString()),
                            record.get("polarity").asBoolean(),
                            EpistemicStatus.fromGraph(record.get("epistemicStatus").asString()),
                            record.get("confidence").asDouble(),
                            parseSource(record),
                            record.get("batch").isNull() ? null : record.get("batch").asString(),

                            record.get("updatedAt").isNull() ? null
                                    : record.get("updatedAt").asZonedDateTime().toInstant(),
                            record.get("lastStatusChangedAt").isNull() ? null
                                    : record.get("lastStatusChangedAt").asZonedDateTime().toInstant(),

                            record.get("priority").asInt()
                    ))
            );
        }
    }

    public static boolean claimExistsRaw(String userId, ExtractedRelation r) {
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim)
        WHERE c.predicate = $pred
          AND c.objectId = $obj
          AND c.quantifier = $quant
          AND c.polarity = $pol
          AND c.legacy = false
        RETURN count(c) > 0 AS exists
        """;
        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, Map.of(
                                    "uid", userId,
                                    "pred", r.predicateType().name(),
                                    "obj", r.objectId(),
                                    "quant", r.quantifier().name(),
                                    "pol", r.polarity()
                            ))
                            .single()
                            .get("exists")
                            .asBoolean() );
        }
    }

    public static Source parseSource(Record record) {
        if (record.get("source").isNull()) {
            return Source.USER_STATEMENT;
        }
        String raw = record.get("source").asString().toUpperCase();
        return switch (raw) {
            case "USER", "USER_STATEMENT", "V2" -> Source.USER_STATEMENT;
            case "QUESTION" -> Source.QUESTION;
            case "SYSTEM" -> Source.SYSTEM;
            case "SELF_CORRECTION" -> Source.SELF_CORRECTION;
            default -> Source.SYSTEM; // 兜底，防脏数据
        };
    }



    @Override
    public AnswerResult answerByRelation(String userId, ExtractedRelation relation) {
        String claimKey = keyCodec.buildExtractRelKey(relation);

        Optional<DominantClaimVO> domOpt = dominantService.loadDominantView(userId, claimKey);

        if (domOpt.isEmpty()) {
            return AnswerResult.unanswered();
        }
        /*
         * ExplainableAnswerBuilder.build
         *     ↓
         * NarrativeService.decide(...)
         *     ↓
         * DefaultNarrativeDecisionEngine
         *     ↓
         * NarrativeDecision
         *     ↓
         * Builder.render()
         */
        return narrativeAnswerBuilder.build(
                userId,
                relation,
                domOpt.get()
        );
    }



}