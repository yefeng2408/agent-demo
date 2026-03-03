package com.yef.agent.graph.answer;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.graph.extract.DefaultRelationCanonicalizer;
import com.yef.agent.graph.extract.GraphRelationExtractor;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.explain.biz.ExplainableAnswerBuilder;
import com.yef.agent.memory.decision.biz.DominantClaimSelector;
import com.yef.agent.memory.decision.biz.DominantDecision;
import com.yef.agent.memory.narrative.build.NarrativeAnswerBuilder;
import com.yef.agent.memory.vo.CanonicalRelation;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.memory.vo.DominantSnapshot;
import com.yef.agent.repository.ClaimEvidenceRepository;
import com.yef.agent.service.DominantService;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static org.neo4j.driver.Values.parameters;

@Slf4j
@Component
public class Neo4jGraphAnswerer implements GraphAnswerer {

    private static Driver driver = null;

    //该数值越小，查询到的claim越多。仲裁阶段的可解释性越强
    private static final double MIN_CONF = 0.2;

    private final KeyCodec keyCodec;
    private final ExplainableAnswerBuilder explainableAnswerBuilder;
    private final DominantService dominantService;
    private final ClaimEvidenceRepository claimEvidenceRepository;
    private final GraphRelationExtractor relationExtractor;
    private final DefaultRelationCanonicalizer relationCanonicalizer;

    public Neo4jGraphAnswerer(Driver driver,
                              KeyCodec keyCodec,
                              ExplainableAnswerBuilder explainableAnswerBuilder,
                              DominantService dominantService,
                              ClaimEvidenceRepository claimEvidenceRepository,
                              GraphRelationExtractor relationExtractor,
                              DefaultRelationCanonicalizer relationCanonicalizer) {
        Neo4jGraphAnswerer.driver = driver;
        this.keyCodec = keyCodec;
        this.explainableAnswerBuilder = explainableAnswerBuilder;
        this.dominantService = dominantService;
        this.claimEvidenceRepository = claimEvidenceRepository;
        this.relationExtractor = relationExtractor;
        this.relationCanonicalizer = relationCanonicalizer;
    }

    @Override
    public AnswerResult extractAsk(String userId, ExtractedRelation r) {
        // 临时写死，只测 ASK_OWNS
        return answerOwns(userId, PredicateType.OWNS, r);
    }

    @Override
    public AnswerResult answer(String userId, String msg) {
        // 临时写死，只测 ASK_OWNS
        return answerAnything(userId, msg);
    }


    private AnswerResult answerOwns(String userId, PredicateType predicate, ExtractedRelation r) {
        // ⭐ candidates 只用于 explain，不用于裁决
        List<ClaimEvidence> candidates = queryClaims(userId, predicate);
        if (candidates == null || candidates.isEmpty()) {
            return AnswerResult.unanswered();
        }
        String slotKey = keyCodec.buildSlotKey2(r);
        Optional<DominantSnapshot> dominantSnapshot = claimEvidenceRepository.loadDominant(userId, slotKey);
        if (dominantSnapshot.isEmpty()) {
            return AnswerResult.unanswered();
        }
        DominantSnapshot dominant = dominantSnapshot.get();

        DominantDecision decision = buildDecisionFromDominant(dominant, candidates);

        // ② 生成可解释回答（只做“表达”）
        AnswerResult result = explainableAnswerBuilder.buildOwnsAnswer(
                userId,
                "特斯拉",
                decision,
                candidates);
        return result;
    }


    private AnswerResult answerAnything(String userId, String msg) {
        // ========= 1️⃣ 先尝试从问题中抽取 relation =========
        List<ExtractedRelation> relations = relationExtractor.extract(userId, msg);

        if (relations != null && !relations.isEmpty()) {

            ExtractedRelation r = relations.stream()
                    .max(Comparator.comparingDouble(ExtractedRelation::confidence))
                    .orElse(relations.get(0));

            Optional<CanonicalRelation> canonicalize = relationCanonicalizer.canonicalize(r, msg);

            if (canonicalize.isPresent()) {
                r = ExtractedRelation.buildCanonicalRelation(canonicalize.get());
                // ⭐ 直接走 graph ask
                AnswerResult graphAnswer = extractAsk(userId, r);
                if (graphAnswer != null && graphAnswer.decision() != null) {
                    return graphAnswer;
                }
            }
        }
        // ========= 2️⃣ Graph 没命中 → unanswered =========
        return AnswerResult.unanswered();
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
                    )).list(new Function<Record, ClaimEvidence>() {
                        @Override
                        public ClaimEvidence apply(Record record) {
                            double momentum = record.containsKey("momentum")
                                    ? record.get("momentum").asDouble()
                                    : 0.0d;

                            Instant lastMomentumAt =
                                    record.get("lastMomentumAt").isNull()
                                            ? null
                                            : record.get("lastMomentumAt")
                                            .asZonedDateTime()
                                            .toInstant();
                            ClaimEvidence claimEvidence = new ClaimEvidence(
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

                                    record.get("priority").asInt(),
                                    momentum,
                                    lastMomentumAt
                            );
                            return claimEvidence;
                        }
                    })
            );
        }
    }

    public static boolean claimExistsRaw(String userId, ExtractedRelation r) {
        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim)
                WHERE 
                      c.subjectId = $subjectId  
                  AND c.predicate = $pred
                  AND c.objectId = $obj
                  AND c.quantifier = $quant
                RETURN count(c) > 0 AS exists
                """;
        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, Map.of(
                                    "uid", userId,
                                    "subjectId", r.subjectId(),
                                    "pred", r.predicateType().name(),
                                    "obj", r.objectId(),
                                    "quant", r.quantifier().name(),
                                    "pol", r.polarity()
                            ))
                            .single()
                            .get("exists")
                            .asBoolean());
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
        log.info("keyCodec.buildSlotKey2(relation) result :{}", keyCodec.buildSlotKey2(relation));
        Optional<DominantClaimVO> domOpt = dominantService.loadDominantView(userId, keyCodec.buildSlotKey2(relation));

        if (domOpt.isEmpty()) {
            return AnswerResult.unanswered();
        }

        return AnswerResult.fromDominant(domOpt.get());
    }


    private DominantDecision buildDecisionFromDominant(DominantSnapshot dominant, List<ClaimEvidence> candidates) {

        if (dominant == null || dominant.claim() == null) {
            return DominantDecision.none();
        }

        ClaimEvidence domClaim = dominant.claim();

        return DominantDecision.builder()
                .dominant(domClaim)
                .epistemicStatus(domClaim.epistemicStatus())
                .decisionSource("GRAPH_DOMINANT")
                .candidates(candidates)   // 仅用于 explain
                .build();
    }

}