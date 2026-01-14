package com.yef.agent.graph.answer;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import static org.neo4j.driver.Values.parameters;

@Component
public class Neo4jGraphAnswerer implements GraphAnswerer {

    private final Driver driver;

    // 你先用最简单的“意图识别”，后面 Step3.2 用 LLM 产 enum 再替换这里
    private static final Pattern Q_NAME = Pattern.compile("你叫啥|你是谁|名字", Pattern.CASE_INSENSITIVE);
    private static final Pattern Q_BORN = Pattern.compile("哪年出生|几岁|生日|出生", Pattern.CASE_INSENSITIVE);
    private static final Pattern Q_ROLE = Pattern.compile("职业|工作|做什么|干什么|岗位|角色", Pattern.CASE_INSENSITIVE);
    private static final Pattern Q_OWNS = Pattern.compile("有车|有没有车|买车|开车|特斯拉|汽车", Pattern.CASE_INSENSITIVE);

    // 建议门槛：0.6；迁移数据 0.95
    private static final double MIN_CONF = 0.6;

    public Neo4jGraphAnswerer(Driver driver) {
        this.driver = driver;
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
        return answerOwns(userId);
    }

    private AnswerResult answerOwns(String userId) {
        List<Record> rows = queryClaims(userId, PredicateType.OWNS.name());
        if (rows.isEmpty()) {
            return AnswerResult.unanswered();
        }
        Record top = rows.get(0); // 已按语义优先级排好
        boolean polarity = top.get("polarity").asBoolean();
        String objectId = top.get("objectId").asString();
        double conf = top.get("confidence").asDouble();

        ExtractedRelation relation = new ExtractedRelation(
                userId,
                PredicateType.OWNS,
                "CAR:any",
                Quantifier.ANY,
                false,
                Math.min(conf, 0.7),
                Source.QUESTION
        );

        // ⚠️ 关键：这里没有 if ANY / ONE 的业务判断
        // 语义已经由排序决定了
        if (!polarity && objectId.startsWith("CAR:any")) {

            return AnswerResult.ok("我目前没有任何汽车。", relation);
        }
        if (polarity) {
            return AnswerResult.ok("我拥有车辆（相关对象：" + objectId + "）。", relation);
        }
        return AnswerResult.ok("我不拥有某些车辆，但是否拥有汽车需要更多信息。", relation);
    }

    /**
     * NAME / BORN_YEAR / HAS_ROLE 这类：ONE + polarity=true 的单值事实
     */
    private AnswerResult answerSingleValue(String userId,
                                           String predicate,
                                           String objectPrefix,
                                           String template) {

        List<Record> rows = queryClaims(userId, predicate);
        // 先找 polarity=true 的最高置信度
        Optional<Record> best = rows.stream()
                .filter(r -> r.get("polarity").asBoolean())
                .filter(r -> r.get("confidence").asDouble() >= MIN_CONF)
                .max(Comparator.comparingDouble(r -> r.get("confidence").asDouble()));

        if (best.isEmpty()) return AnswerResult.unanswered();

        String objectId = best.get().get("objectId").asString(); // e.g. NAME:叶丰
        String value = stripPrefix(objectId, objectPrefix);
        double conf = best.get().get("confidence").asDouble();
        ExtractedRelation relation = new ExtractedRelation(
                userId,
                PredicateType.OWNS,
                "CAR:any",
                Quantifier.ANY,
                false,
                Math.min(conf, 0.7),
                Source.QUESTION
        );

        return AnswerResult.ok(String.format(template, value), relation);
    }

    /**
     * OWNS：优先 ANY 的否定，再找具体肯定
     */
    private AnswerResult answerOwnsCar(String userId) {
        List<Record> rows = queryClaims(userId, "OWNS");

        // 1) ANY 且否定：我没有任何车
        Optional<Record> deniedAny = rows.stream()
                .filter(r -> "ANY".equalsIgnoreCase(r.get("quantifier").asString()))
                .filter(r -> !r.get("polarity").asBoolean())
                .filter(r -> r.get("confidence").asDouble() >= MIN_CONF)
                .max(Comparator.comparingDouble(r -> r.get("confidence").asDouble()));
        double conf = deniedAny.get().get("confidence").asDouble();

        ExtractedRelation extractedRelation = new ExtractedRelation(
                userId,
                PredicateType.OWNS,
                "CAR:any",
                Quantifier.ANY,
                false,
                Math.min(conf, 0.7),
                Source.QUESTION
        );

        if (deniedAny.isPresent()) {
            return AnswerResult.ok("我目前没有任何汽车。", extractedRelation);
        }
        // 2) 找具体肯定（例如 BRAND:Tesla / CAR:xxx）
        Optional<Record> bestPositive = rows.stream()
                .filter(r -> r.get("polarity").asBoolean())
                .filter(r -> r.get("confidence").asDouble() >= MIN_CONF)
                .max(Comparator.comparingDouble(r -> r.get("confidence").asDouble()));

        if (bestPositive.isPresent()) {
            String objectId = bestPositive.get().get("objectId").asString();
            return AnswerResult.ok("我有车（相关对象：" + objectId + "）。", extractedRelation);
        }

        // 3) 有具体否定但没有 ANY（比如否定特斯拉，但不代表没车）
        boolean deniedSome = rows.stream()
                .anyMatch(r -> !r.get("polarity").asBoolean() && r.get("confidence").asDouble() >= MIN_CONF);

        if (deniedSome) {
            return AnswerResult.ok("我能确定我不拥有其中某些车（例如特定品牌），但是否有车需要更多信息。", extractedRelation);
        }

        return AnswerResult.unanswered();
    }

    private List<Record> queryClaims(String userId, String predicate) {
        String cypher = """
        MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim)
        WHERE c.predicate = $pred AND c.confidence >= $minConf
        WITH c,
        CASE
          WHEN toUpper(c.quantifier) = 'ANY' AND c.polarity = false THEN 0
          WHEN toUpper(c.quantifier) = 'ANY' AND c.polarity = true  THEN 1
          WHEN toUpper(c.quantifier) = 'ONE' AND c.polarity = true  THEN 2
          WHEN toUpper(c.quantifier) = 'ONE' AND c.polarity = false THEN 3
          ELSE 9
        END AS pri
        RETURN c.objectId AS objectId,
               c.quantifier AS quantifier,
               c.polarity AS polarity,
               c.confidence AS confidence,
               c.updatedAt AS updatedAt,
               pri AS pri
        ORDER BY pri ASC, c.confidence DESC, c.updatedAt DESC
        """;

        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, parameters("uid", userId, "pred", predicate, "minConf", MIN_CONF)).list()
            );
        }
    }

    private static String stripPrefix(String s, String prefix) {
        if (s == null) return "";
        if (prefix == null) return s;
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }
}