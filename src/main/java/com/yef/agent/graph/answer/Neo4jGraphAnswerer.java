package com.yef.agent.graph.answer;

import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.memory.EpistemicStatus;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.neo4j.driver.Values.parameters;

@Component
public class Neo4jGraphAnswerer implements GraphAnswerer {

    private static Driver driver = null;

    // 你先用最简单的“意图识别”，后面 Step3.2 用 LLM 产 enum 再替换这里
    private static final Pattern Q_NAME = Pattern.compile("你叫啥|你是谁|名字", Pattern.CASE_INSENSITIVE);
    private static final Pattern Q_BORN = Pattern.compile("哪年出生|几岁|生日|出生", Pattern.CASE_INSENSITIVE);
    private static final Pattern Q_ROLE = Pattern.compile("职业|工作|做什么|干什么|岗位|角色", Pattern.CASE_INSENSITIVE);
    private static final Pattern Q_OWNS = Pattern.compile("有车|有没有车|买车|开车|特斯拉|汽车", Pattern.CASE_INSENSITIVE);

    // 建议门槛：0.6；迁移数据 0.95
    private static final double MIN_CONF = 0.2;

    public Neo4jGraphAnswerer(Driver driver) {
        Neo4jGraphAnswerer.driver = driver;
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

        // 1) 召回：拿到同 predicate 的候选（阈值低一点，别丢证据）
        List<ClaimEvidence> candidates = queryClaims(userId, PredicateType.OWNS);

        if (candidates.isEmpty()) {
            return AnswerResult.unanswered();
        }


        DominantDecision decision =
                dominantClaimSelector.select(claims);

        // 2) 聚合成 “同 proposition 的正反对”（这里你当前只问 Tesla，就按 objectId=BRAND:Tesla 聚合也行）
        ClaimEvidence bestTrue  = bestBy(candidates, /*polarity*/ true);
        ClaimEvidence bestFalse = bestBy(candidates, /*polarity*/ false);

        // 3) 三态裁决：只对 CONFIRMED 给肯定/否定，否则不确定
        boolean trueConfirmed  = bestTrue  != null && bestTrue.epistemicStatus() == EpistemicStatus.CONFIRMED;
        boolean falseConfirmed = bestFalse != null && bestFalse.epistemicStatus() == EpistemicStatus.CONFIRMED;

        // 3.1 单边确认
        if (trueConfirmed && !falseConfirmed) {
            return AnswerResult.ok("我目前拥有一辆特斯拉。", relationFrom(bestTrue), citationsOf(bestTrue), null);
        }
        if (falseConfirmed && !trueConfirmed) {
            return AnswerResult.ok("我目前并不拥有特斯拉。", relationFrom(bestFalse), citationsOf(bestFalse), null);
        }

        // 3.2 双边都确认（极少，但必须处理）
        if (trueConfirmed && falseConfirmed) {
            return AnswerResult.ok(
                    "关于我是否拥有特斯拉，目前存在相互矛盾且都很强的证据，我需要进一步确认（例如最近一次变更发生在什么时候）。",
                    relationFrom(stronger(bestTrue,bestFalse)),
                    citationsOfBoth(bestTrue,bestFalse),
                    List.of("你最后一次说“有/没有特斯拉”分别是什么时候？")
            );
        }

        // 3.3 都没确认：不确定态（这里就是你现在经常遇到的情况）
        return AnswerResult.ok(
                "关于我是否拥有特斯拉，目前证据强度不足，尚未形成稳定结论。",
                relationFrom(bestTrue != null ? bestTrue : bestFalse),
                citationsOfTop2(bestTrue, bestFalse),
                List.of("你是想更新你的最新状态吗？（例如：现在是否拥有特斯拉）")
        );
    }


    private ExtractedRelation relationFrom(ClaimEvidence bestTrue) {
        return ExtractedRelation.fromEvidence(bestTrue,Source.QUESTION);
    }

    private List<Citation> citationsOf(ClaimEvidence bestTrue) {
        if (bestTrue == null) return List.of();
        return List.of(Citation.from(bestTrue));
    }

    /**
     * 双方都很强，我摊牌
     * @param bestTrue
     * @param bestFalse
     * @return
     */
    private List<Citation> citationsOfBoth(
            ClaimEvidence bestTrue,
            ClaimEvidence bestFalse) {
        List<Citation> list = new ArrayList<>();
        if (bestTrue != null) list.add(Citation.from(bestTrue));
        if (bestFalse != null) list.add(Citation.from(bestFalse));
        return list;
    }

    /**
     * 不确定态，但给你最有代表性的证据
     * @param bestTrue
     * @param bestFalse
     * @return
     */
    private List<Citation> citationsOfTop2(
            ClaimEvidence bestTrue,
            ClaimEvidence bestFalse) {
        return Stream.of(bestTrue, bestFalse)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ClaimEvidence::confidence).reversed())
                .limit(2)
                .map(Citation::from)
                .toList();
    }

    /**
     * epistemicStatus 优先级 + confidence + 新鲜度（recently time）。 谁更有资格代表正方 / 反方
     *
     * @param list
     * @param polarity
     * @return
     */
    private ClaimEvidence bestBy(List<ClaimEvidence> list, boolean polarity) {
        return list.stream()
                .filter(c -> c.polarity() == polarity)
                .sorted(
                        Comparator
                                // 1️⃣ CONFIRMED 永远优先
                                .comparing((ClaimEvidence c) -> c.epistemicStatus() == EpistemicStatus.CONFIRMED)
                                .reversed()
                                // 2️⃣ 置信度
                                .thenComparing(ClaimEvidence::confidence)
                                .reversed()
                                // 3️⃣ 最近更新时间
                                .thenComparing(ClaimEvidence::updatedAt, Comparator.reverseOrder())
                )
                .findFirst()
                .orElse(null);
    }

    private ClaimEvidence stronger(ClaimEvidence a, ClaimEvidence b) {
        if (a == null) return b;
        if (b == null) return a;

        // 1️⃣ 先看置信度
        int byConf = Double.compare(a.confidence(), b.confidence());
        if (byConf != 0) {
            return byConf > 0 ? a : b;
        }
        // 2️⃣ 再看更新时间
        return a.updatedAt().isAfter(b.updatedAt()) ? a : b;
    }


    String explainOwns(ClaimEvidence c) {
        if (c.quantifier() == Quantifier.ANY && !c.polarity()) {
            return "我目前没有任何车辆。";
        }
        if (c.quantifier() == Quantifier.ANY && c.polarity()) {
            return "我至少拥有一辆车。";
        }
        if (c.quantifier() == Quantifier.ONE && c.polarity()) {
            return "我拥有一辆车（" + c.objectId() + "）。";
        }
        if (c.quantifier() == Quantifier.ONE && !c.polarity()) {
            return "我目前不拥有这辆车（" + c.objectId() + "）。";
        }
        return "关于我是否拥有车辆，目前信息不足。";
    }

    /**
     * NAME / BORN_YEAR / HAS_ROLE 这类：ONE + polarity=true 的单值事实
     */
    private AnswerResult answerSingleValue(String userId,
                                           PredicateType predicate,
                                           String objectPrefix,
                                           String template) {

        List<ClaimEvidence> evidences = queryClaims(userId, predicate);
        ClaimEvidence top = evidences.get(0);
        // 先找 polarity=true 的最高置信度
        Optional<ClaimEvidence> best = evidences.stream()
                .filter(e -> e.polarity())
                .filter(e -> e.confidence() >= MIN_CONF)
                .max(Comparator.comparingDouble(e -> e.confidence()));

        if (best.isEmpty()) return AnswerResult.unanswered();

        List<Citation> citations = toCitations(evidences);

        String objectId = evidences.get(0).objectId(); // e.g. NAME:叶丰
        String value = stripPrefix(objectId, objectPrefix);
        ExtractedRelation relation = ExtractedRelation.fromEvidence(top, Source.QUESTION);
        return AnswerResult.ok(String.format(template, value), relation, citations, null);
    }

    /**
     * 临时测试方法  OWNS：优先 ANY 的否定，再找具体肯定
     */
    private AnswerResult answerOwnsCar(String userId) {
        List<ClaimEvidence> evidences = queryClaims(userId, PredicateType.OWNS);
        ClaimEvidence top = evidences.get(0);

        // 1) ANY 且否定：我没有任何车
        Optional<ClaimEvidence> deniedAny = evidences.stream()
                .filter(e -> "ANY".equalsIgnoreCase(e.quantifier().name()))
                .filter(e -> !e.polarity())
                .filter(e -> e.confidence() >= MIN_CONF)
                .max(Comparator.comparingDouble(r -> r.confidence()));

        ExtractedRelation relation = ExtractedRelation.fromEvidence(top, Source.QUESTION);
        List<Citation> citations = toCitations(evidences);

        if (deniedAny.isPresent()) {
            return AnswerResult.ok("我目前没有任何汽车。", relation, citations, null);
        }
        // 2) 找具体肯定（例如 BRAND:Tesla / CAR:xxx）
        Optional<ClaimEvidence> bestPositive = evidences.stream()
                .filter(e -> e.polarity())
                .filter(e -> e.confidence() >= MIN_CONF)
                .max(Comparator.comparingDouble(e -> e.confidence()));

        if (bestPositive.isPresent()) {
            String objectId = bestPositive.get().objectId();
            return AnswerResult.ok("我有车（相关对象：" + objectId + "）。", relation, citations, null);
        }

        // 3) 有具体否定但没有 ANY（比如否定特斯拉，但不代表没车）
        boolean deniedSome = evidences.stream()
                .anyMatch(e -> !e.polarity() && e.confidence() >= MIN_CONF);

        if (deniedSome) {
            return AnswerResult.ok("我能确定我不拥有其中某些车（例如特定品牌），但是否有车需要更多信息。", relation, citations, null);
        }
        return AnswerResult.unanswered();
    }

    private List<Citation> toCitations(List<ClaimEvidence> rows) {
        List<Citation> citations = new ArrayList<>();
        for (ClaimEvidence claimEvi : rows) {
            Citation citation = new Citation(
                    claimEvi.predicate().name(),
                    claimEvi.subjectId(),
                    claimEvi.objectId(),
                    claimEvi.quantifier().name(),
                    claimEvi.polarity(),
                    claimEvi.confidence(),
                    claimEvi.source().name(),
                    claimEvi.batch(),
                    claimEvi.updatedAt()
            );
            citations.add(citation);
        }
        return citations;
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
                            record.get("subjectId").asString(),
                            PredicateType.valueOf(record.get("predicate").asString()),
                            record.get("objectId").asString(),
                            Quantifier.valueOf(record.get("quantifier").asString()),
                            record.get("polarity").asBoolean(),
                            //EpistemicStatus.valueOf(record.get("epistemicStatus").asString()),
                            EpistemicStatus.fromGraph(record.get("epistemicStatus").asString()),
                            record.get("confidence").asDouble(),
                            parseSource(record),
                            record.get("batch").isNull() ? null : record.get("batch").asString(),
                            record.get("updatedAt").isNull() ? null
                                    : record.get("updatedAt").asZonedDateTime().toInstant(),
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

    private static String stripPrefix(String s, String prefix) {
        if (s == null) return "";
        if (prefix == null) return s;
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }
}