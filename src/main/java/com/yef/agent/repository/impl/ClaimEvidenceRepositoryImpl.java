package com.yef.agent.repository.impl;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.component.Neo4jMappingSupport;
import com.yef.agent.graph.ExtractedRelation;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.graph.eum.SourceAdapter;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.pipeline.TransitionReason;
import com.yef.agent.memory.vo.DominantSnapshot;
import com.yef.agent.memory.vo.OverriddenEdgeVO;
import com.yef.agent.repository.ClaimEvidenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.types.Node;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.neo4j.driver.Values.parameters;

@Slf4j
@Component
public class ClaimEvidenceRepositoryImpl implements ClaimEvidenceRepository {

    private final Driver driver;
    private final KeyCodec keyCodec;
    private final Neo4jClient neo4jClient;
    private final Neo4jMappingSupport neo4jMappingSupport;

    public ClaimEvidenceRepositoryImpl(Driver driver, KeyCodec keyCodec, Neo4jClient neo4jClient, Neo4jMappingSupport neo4jMappingSupport) {
        this.driver = driver;
        this.keyCodec = keyCodec;
        this.neo4jClient = neo4jClient;
        this.neo4jMappingSupport = neo4jMappingSupport;
    }

    @Override
    public List<ClaimEvidence> loadActiveBySlot(String userId, String slotKey) {
        String cypher = """
                    MATCH (u:User {id:$userId})-[:HAS_CLAIM]->(c:ClaimEvidence {slotKey:$slotKey})
                    WHERE NOT (c)-[:OVERRIDDEN_BY]->(:ClaimEvidence)
                    RETURN c
                """;
        return (List<ClaimEvidence>) neo4jClient.query(cypher)
                .bind(userId).to("userId")
                .bind(slotKey).to("slotKey")
                .fetchAs(ClaimEvidence.class)
                .mappedBy(neo4jMappingSupport::mapToClaimEvidence)
                .all();
    }


    @Override
    public int countRecentDominantSwitches(String userId, String slotKey, long switchWindowSec) {
        return 0;
    }

    public void touchDominantMeta(String userId, String slotKey, String reason) {
        String cypher = """
                MATCH (u:User {id:$userId})-[d:HAS_DOMINANT {slotKey:$slotKey}]->(:ClaimEvidence)
                SET d.lastSwitchAt = datetime($now),
                    d.lastSwitchReason = $reason
                """;

        neo4jClient.query(cypher)
                .bind(userId).to("userId")
                .bind(slotKey).to("slotKey")
                .bind(Instant.now()).to("now")
                .bind(reason).to("reason")
                .run();
    }

    @Override
    public void appendDominantSwitchEvent(String userId, String slotKey, String curKey, String s, TransitionReason reason) {
        String cypher = """
                       CREATE (e:DominantSwitchEvent {
                                                        id: randomUUID(),
                                                        userId:$userId,
                                                        slotKey:$slotKey,
                                                        at: datetime($now),
                                                        fromKey:$fromKey,
                                                        toKey:$toKey,
                                                        reason:$reason
                                                      })
                """;
    }

    @Override
    public void updateMomentum(String claimKey, double newMomentum, Instant now) {
        String cypher = """
                MATCH (c:ClaimEvidence {evidenceKey:$claimKey})
                SET c.momentum = $momentum,
                    c.lastMomentumAt = datetime($now)
                """;

        Map<String, Object> params = Map.of(
                "claimKey", claimKey,
                "momentum", newMomentum,
                "now", now.toString()
        );

        neo4jClient.query(cypher)
                .bindAll(params)
                .run();
    }

    @Override
    public Optional<ClaimEvidence> loadDominantClaim(String userId, String slotKey) {
        String cypher = """
                    MATCH (s:ClaimSlot {key:$slotKey})
                                          -[:DOMINANT]->(d:ClaimEvidence)
                                    RETURN d
                                    LIMIT 1
                """;
        Map<String, Object> params = Map.of(
                //"userId", userId,
                "slotKey", slotKey
        );

        List<ClaimEvidence> list = (List<ClaimEvidence>) neo4jClient.query(cypher)
                .bindAll(params)
                .fetchAs(ClaimEvidence.class)
                .mappedBy((typeSystem, record) ->
                        neo4jMappingSupport.mapNode(
                                record.get("d").asNode(),
                                ClaimEvidence.class
                        )
                )
                .all();
        return list.stream().findFirst();
    }


    @Override
    public Optional<ClaimEvidence> findBestOpposite(
            String userId,
            String slotKey,
            boolean newPolarity
    ) {

        String cypher = """
                MATCH (u:User {id:$userId})-[:HAS_CLAIM]->
                      (c:ClaimEvidence {slotKey:$slotKey})
                WHERE c.polarity <> $newPolarity
                  AND NOT (c)-[:OVERRIDDEN_BY]->(:ClaimEvidence)
                RETURN c
                ORDER BY c.confidence DESC
                LIMIT 1
                """;

        Map<String, Object> params = Map.of(
                "userId", userId,
                "slotKey", slotKey,
                "newPolarity", newPolarity
        );

        List<ClaimEvidence> list =
                (List<ClaimEvidence>) neo4jClient.query(cypher)
                        .bindAll(params)
                        .fetchAs(ClaimEvidence.class)
                        .mappedBy((ts, record) ->
                                neo4jMappingSupport.mapNode(
                                        record.get("c").asNode(),
                                        ClaimEvidence.class))
                        .all();

        return list.stream().findFirst();
    }

    @Override
    public List<ClaimEvidence> loadAllBySlot(String userId, String slotKey) {

        String cypher = """
                MATCH (u:User {userId:$userId})
                      -[:HAS_CLAIM]->
                      (c:ClaimEvidence {slotKey:$slotKey})
                RETURN c
                """;

        Map<String, Object> params = Map.of(
                "userId", userId,
                "slotKey", slotKey
        );

        return (List<ClaimEvidence>) neo4jClient.query(cypher)
                .bindAll(params)
                .fetchAs(ClaimEvidence.class)
                .mappedBy((typeSystem, record) ->
                        neo4jMappingSupport.mapNode(record.get("c").asNode(), ClaimEvidence.class)
                ).all();
    }


    @Override
    public ClaimEvidence loadEvidenceClaimByClaimKey(String userId, String claimKey) {
        KeyCodec.DecodedKey key = keyCodec.decode(claimKey);
        return loadClaimEvidence(
                userId,
                key.subjectId(),
                key.predicate(),
                key.objectId(),
                key.quantifier(),
                key.polarity()
        );
    }


    @Override
    public ClaimEvidence loadClaimEvidence(
            String userId,
            String subjectId,
            PredicateType predicate,
            String objectId,
            Quantifier quantifier,
            boolean polarity) {
        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
                  subjectId:$sid,
                  predicate:$pred,
                  objectId:$oid,
                  quantifier:$q,
                  polarity:$pol
                })
                RETURN
                  c.subjectId AS subjectId,
                  c.predicate AS predicate,
                  c.objectId AS objectId,
                  c.quantifier AS quantifier,
                  c.polarity AS polarity,
                  c.epistemicStatus AS epistemicStatus,
                  coalesce(c.confidence, 0.5) AS confidence,
                  c.source AS source,
                  c.batch AS batch,
                  c.updatedAt AS updatedAt,
                  c.lastStatusChangedAt AS lastStatusChangedAt,
                  coalesce(c.priority, 0) AS priority
                """;
        try (Session session = driver.session()) {
            List<Record> records = session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "sid", subjectId,
                            "pred", predicate.name(),
                            "oid", objectId,
                            "q", quantifier.name(),
                            "pol", polarity
                    )).list()
            );
            if (CollectionUtils.isEmpty(records)) {
                return null;
            }
            Record rec = records.get(0);
            // 注意：Neo4j 返回的类型你可按你项目里已有的工具再封装一下
            String predStr = rec.get("predicate").asString();
            String qStr = rec.get("quantifier").asString();
            String statusStr = rec.get("epistemicStatus").isNull() ? null : rec.get("epistemicStatus").asString();
            EpistemicStatus epistemicStatus = EpistemicStatus.fromGraph(statusStr);

            double momentum = rec.containsKey("momentum")
                    ? rec.get("momentum").asDouble()
                    : 0.0d;

            Instant lastMomentumAt =
                    rec.get("lastMomentumAt").isNull()
                            ? null
                            : rec.get("lastMomentumAt")
                            .asZonedDateTime()
                            .toInstant();


            ClaimEvidence claimEvidence = new ClaimEvidence(
                    rec.get("elementId").asString(),
                    rec.get("subjectId").asString(),
                    PredicateType.valueOf(predStr),
                    rec.get("objectId").asString(),
                    Quantifier.valueOf(qStr),
                    rec.get("polarity").asBoolean(),
                    epistemicStatus,
                    rec.get("confidence").asDouble(),
                    rec.get("source").isNull() ? null : SourceAdapter.fromRaw(rec.get("source").asString()),
                    rec.get("batch").isNull() ? null : rec.get("batch").asString(),
                    rec.get("updatedAt").isNull() ? null : rec.get("updatedAt").asZonedDateTime().toInstant(),
                    rec.get("lastStatusChangedAt").isNull() ? null : rec.get("lastStatusChangedAt").asZonedDateTime().toInstant(),
                    rec.get("priority").asInt(),
                    momentum,
                    lastMomentumAt

            );
            log.info("ClaimEvidenceRepository.getClaimEvidence result:{}", claimEvidence);
            return claimEvidence;
        }
    }


    //只更新置信度
    @Override
    public ClaimEvidence updateConfidence(String claimKey, double confidence) {
        KeyCodec.DecodedKey decode = keyCodec.decode(claimKey);

        String cypher = """
                MATCH (c:Claim {
                  subjectId:$sid,
                  predicate:$pred,
                  objectId:$oid,
                  quantifier:$q,
                  polarity:$pol
                })
                SET c.confidence = $conf,
                    c.supportCount = coalesce(c.supportCount,0) + 1,
                    c.updatedAt = datetime()
                RETURN c
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, parameters(
                        "sid", decode.subjectId(),
                        "pred", decode.predicate().name(),
                        "oid", decode.objectId(),
                        "q", decode.quantifier().name(),
                        "pol", decode.polarity(),
                        "conf", confidence
                )).consume();
                return null;
            });
        }
        return null;
    }


    @Override
    public void updateEpistemicStatus(String claimKey,
                                      EpistemicStatus epistemicStatus) {
        KeyCodec.DecodedKey decode = keyCodec.decode(claimKey);
        String cypher = """
                MATCH (c:Claim {
                  subjectId:$sid,
                  predicate:$pred,
                  objectId:$oid,
                  quantifier:$q,
                  polarity:$pol
                })
                SET c.epistemicStatus = $eStatus,
                    c.lastStatusChangedAt = datetime(),
                    c.updatedAt = datetime()
                RETURN c
                """;
        try (Session session = driver.session()) {
            session.executeWrite(tx ->
                    tx.run(cypher, parameters(
                            "sid", decode.subjectId(),
                            "pred", decode.predicate().name(),
                            "oid", decode.objectId(),
                            "q", decode.quantifier().name(),
                            "pol", decode.polarity(),
                            "eStatus", epistemicStatus.name()
                    )).consume()
            );
        }
    }


    // ⚠️ 现在：通常返回 1 条
    // ⚠️ 未来：多表达 / 多来源 / 多版本
    @Deprecated
    @Override
    public List<ClaimEvidence> loadAllByClaimKey(String userId, String claimKey) {
        KeyCodec.DecodedKey decode = keyCodec.decode(claimKey);
        String cypher = """
                MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim {
                  subjectId:$sid,
                  predicate:$pred,
                  objectId:$oid,
                  quantifier:$q
                })
                RETURN
                  c.subjectId AS subjectId,
                  c.predicate AS predicate,
                  c.objectId AS objectId,
                  c.quantifier AS quantifier,
                  c.polarity AS polarity,
                  c.epistemicStatus AS epistemicStatus,
                  coalesce(c.confidence, 0.5) AS confidence,
                  c.source AS source,
                  c.batch AS batch,
                  c.updatedAt AS updatedAt,
                  c.lastStatusChangedAt AS lastStatusChangedAt,
                  coalesce(c.priority, 0) AS priority
                ORDER BY coalesce(c.confidence, 0.5) DESC, coalesce(c.priority, 0) DESC
                """;

        try (Session session = driver.session()) {
            List<Record> records = session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "sid", decode.subjectId(),
                            "pred", decode.predicate().name(),
                            "oid", decode.objectId(),
                            "q", decode.quantifier().name()
                    )).list()
            );
            if (CollectionUtils.isEmpty(records)) {
                return List.of();
            }
            return records.stream().map(rec -> {
                String predStr = rec.get("predicate").asString();
                String qStr = rec.get("quantifier").asString();
                String statusStr = rec.get("epistemicStatus").isNull() ? null : rec.get("epistemicStatus").asString();
                EpistemicStatus epistemicStatus = EpistemicStatus.fromGraph(statusStr);

                double momentum = rec.containsKey("momentum")
                        ? rec.get("momentum").asDouble()
                        : 0.0d;

                Instant lastMomentumAt =
                        rec.get("lastMomentumAt").isNull()
                                ? null
                                : rec.get("lastMomentumAt")
                                .asZonedDateTime()
                                .toInstant();


                return new ClaimEvidence(
                        rec.get("elementId").asString(),
                        rec.get("subjectId").asString(),
                        PredicateType.valueOf(predStr),
                        rec.get("objectId").asString(),
                        Quantifier.valueOf(qStr),
                        rec.get("polarity").asBoolean(),
                        epistemicStatus,
                        rec.get("confidence").asDouble(),
                        rec.get("source").isNull() ? null : SourceAdapter.fromRaw(rec.get("source").asString()),
                        rec.get("batch").isNull() ? null : rec.get("batch").asString(),
                        rec.get("updatedAt").isNull() ? null : rec.get("updatedAt").asZonedDateTime().toInstant(),
                        rec.get("lastStatusChangedAt").isNull() ? null : rec.get("lastStatusChangedAt").asZonedDateTime().toInstant(),
                        rec.get("priority").asInt(),
                        momentum,
                        lastMomentumAt
                );
            }).toList();
        }
    }


    // ===== DOMINANT 相关 =====

    /**
     * 删除当前 claimSlot 上的 dominant（如果存在）
     */
    @Override
    public void clearDominant(String userId, String claimSlot) {
        String cypher = """
                MATCH (slot:ClaimSlot {key:$slot})-[r:DOMINANT]->()
                DELETE r
                """;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, parameters(
                        "slot", claimSlot
                )).consume();   // ⭐ 必须在事务内部消费

                return null;    // ⭐ lambda 必须返回
            });
        }

    }

    /**
     * 写入新的 dominant 语义
     * Claim = 世界的候选解释
     * BeliefState  = 当前世界观
     * StatusTransition = 信念成熟轨迹
     */
    @Override
    public void writeDominant(String userId,
                              String slotKey,
                              String claimKey,
                              double supportConfidenceAt,
                              EpistemicStatus status,
                              String reason) {
        log.info("------>writeDominant|param userId:{},slotKey:{}, claimKey:{},status:{},reason:{}",
                userId, slotKey, claimKey, status, reason);
        String cypher = """
                MATCH (slot:ClaimSlot {key:$slotKey})
                MATCH (c:Claim {claimKey:$claimKey})
                
                // 当前 dominant（可能不存在）
                OPTIONAL MATCH (slot)-[oldRel:DOMINANT]->(oldBs:BeliefState)
                OPTIONAL MATCH (oldBs)-[:BASED_ON]->(oldClaim:Claim)
                
                // 1) 如果当前 dominant 还是同一个 claim：只 touch，不建新节点，不写迁移
                FOREACH (_ IN CASE WHEN oldClaim IS NOT NULL AND oldClaim.claimKey = $claimKey THEN [1] ELSE [] END |
                  SET oldBs.lastActiveAt = datetime(),
                      oldBs.reason = $reason
                  // 你要的话，也可以记录“当时的 claim.confidence 快照”
                  // SET oldBs.supportConfidenceAt = $supportConfidenceAt
                )
                
                // 2) 如果当前 dominant 不存在 或者 claimKey 不同：才创建新 belief 并切换
                FOREACH (_ IN CASE WHEN oldClaim IS NULL OR oldClaim.claimKey <> $claimKey THEN [1] ELSE [] END |
                  CREATE (newBs:BeliefState {
                    id: randomUUID(),
                    slotKey: $slotKey,
                    dominantClaimKey: $claimKey,
                    reason: $reason,
                    since: datetime(),
                    lastActiveAt: datetime()
                    // ✅ v4 建议：BeliefState 不再承载 epistemicStatus / confidence（避免双写）
                  })
                  MERGE (newBs)-[:BASED_ON]->(c)
                
                  FOREACH (__ IN CASE WHEN oldBs IS NULL THEN [] ELSE [1] END |
                    MERGE (oldBs)-[t:TRANSITION_TO]->(newBs)
                    SET t.at = datetime(), t.reason = $reason
                  )
                
                  FOREACH (__ IN CASE WHEN oldRel IS NULL THEN [] ELSE [1] END |
                    DELETE oldRel
                  )
                
                  MERGE (slot)-[:DOMINANT]->(newBs)
                )
                """;
        try (Session session = driver.session()) {
            session.executeWrite(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "slotKey", slotKey,
                            "claimKey", claimKey,
                            "confidence", supportConfidenceAt,
                            "status", status.name(),
                            "reason", reason
                    )).consume()
            );
        }
    }

    /**
     * 写 OVERRIDDEN_BY 路径
     */
    @Override
    public void writeOverriddenBy(String userId,
                                  String fromClaimKey,
                                  String toClaimKey,
                                  String reason,
                                  double intentConfidence) {

        String cypher = """
                    MATCH (u:User {id:$userId})-[:HAS_CLAIM]->(from:ClaimEvidence {evidenceKey:$fromKey})
                    MATCH (u)-[:HAS_CLAIM]->(to:ClaimEvidence {evidenceKey:$toKey})
                    MERGE (from)-[r:OVERRIDDEN_BY]->(to)
                    SET r.reason = $reason,
                        r.intentConfidence = $intentConfidence,
                        r.updatedAt = datetime($now)
                """;

        Map<String, Object> params = new java.util.HashMap<>();
        params.put("userId", userId);
        params.put("fromKey", fromClaimKey);
        params.put("toKey", toClaimKey);
        params.put("reason", reason);
        params.put("intentConfidence", intentConfidence);
        params.put("now", java.time.Instant.now().toString());

        neo4jClient.query(cypher).bindAll(params).run();
    }


    /**
     * 查询当前 dominant（回答阶段用）
     */
    @Override
    public Optional<DominantSnapshot> loadDominant(String userId, String slotKey) {
        String cypher = """
                MATCH (slot:ClaimSlot {key:$slot})
                MATCH (slot)-[:DOMINANT]->(b:BeliefState)
                MATCH (b)-[:BASED_ON]->(c:Claim)
                RETURN
                    // ---- BeliefState 侧：dominant 语义 ----
                    b.since      AS dominantSince,
                    b.confidence AS supportConfidenceAt,
                    b.reason     AS reason,
                
                    // ---- Claim 侧：claim 本体字段 ----
                    elementId(c) AS elementId,
                    c.subjectId AS subjectId,
                    c.predicate AS predicate,
                    c.objectId AS objectId,
                    c.quantifier AS quantifier,
                    c.polarity AS polarity,
                    c.epistemicStatus AS epistemicStatus,
                    c.confidence AS confidence,
                    c.source AS source,
                    c.batch AS batch,
                    c.updatedAt AS updatedAt,
                    c.lastStatusChangedAt AS lastStatusChangedAt,
                    c.priority AS priority
                """;
        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "slot", slotKey
                    )).list().stream().findFirst()
            ).map(this::mapToDominant);
        }
    }

    private DominantSnapshot mapToDominant(Record r) {
        Integer priority = r.get("priority").isNull() ? null : r.get("priority").asInt();
        double momentum = r.containsKey("momentum") ? r.get("momentum").asDouble() : 0.0d;

        Instant lastMomentumAt = r.get("lastMomentumAt").isNull()
                        ? null
                        : r.get("lastMomentumAt")
                        .asZonedDateTime()
                        .toInstant();

        String batch = r.containsKey("batch") ? r.get("batch").asString() : "";

        Instant dominantSince = null;
        if (!r.get("dominantSince").isNull()) {
            dominantSince = r.get("dominantSince").asZonedDateTime().toInstant();
        }

        double supportConfidenceAt = r.get("supportConfidenceAt").isNull()
                ? 0.0d : r.get("supportConfidenceAt").asDouble();

        String reason = r.get("reason").isNull() ? null : r.get("reason").asString();

        ClaimEvidence claim = new ClaimEvidence(
                r.get("elementId").asString(),
                r.get("subjectId").asString(),
                PredicateType.valueOf(r.get("predicate").asString()),
                r.get("objectId").asString(),
                Quantifier.valueOf(r.get("quantifier").asString()),
                r.get("polarity").asBoolean(),
                EpistemicStatus.valueOf(r.get("epistemicStatus").asString()),
                r.get("confidence").asDouble(),
                Source.valueOf(r.get("source").asString()),
                batch,
                r.get("updatedAt").asZonedDateTime().toInstant(),
                r.get("lastStatusChangedAt").isNull() ? null :
                        r.get("lastStatusChangedAt").asZonedDateTime().toInstant(),
                priority,
                momentum,
                lastMomentumAt
        );

        return new DominantSnapshot(
                claim,
                dominantSince,
                supportConfidenceAt,
                reason
        );
    }


    @Override
    public List<OverriddenEdgeVO> loadOverrideHistory(String claimEvidenceId) {
        String cypher = """
                MATCH (old:ClaimEvidence {id:$cid})-[r:OVERRIDDEN_BY]->(new:ClaimEvidence)
                RETURN
                  old.id AS fromId,
                  new.id AS toId,
                  r.reason AS reason,
                  r.at AS at
                ORDER BY r.at DESC
                """;
        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, Map.of("cid", claimEvidenceId))
                            .list(r -> new OverriddenEdgeVO(
                                    r.get("fromId").asString(),
                                    r.get("toId").asString(),
                                    r.get("reason").asString(),
                                    r.get("at").asZonedDateTime().toInstant()
                            ))
            );
        }
    }


    /**
     * 判断当前 claimSlot 是否已经存在 dominant
     */
    @Override
    public boolean hasDominant(String userId, String claimSlot) {
        String cypher = """
                MATCH (slot:ClaimSlot {key:$slot})-[:DOMINANT]->(b:BeliefState)
                RETURN count(b) > 0 AS exists
                """;

        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, parameters(
                                    "slot", claimSlot
                            ))
                            .single()
                            .get("exists")
                            .asBoolean()
            );
        }
    }

    @Override
    public Optional<Instant> loadLastSwitchAt(String userId, String slotKey) {
        String cypher = """
                MATCH (u:User {id:$userId})-[d:HAS_DOMINANT {slotKey:$slotKey}]->(:ClaimEvidence)
                RETURN d.lastSwitchAt AS lastSwitchAt
                LIMIT 1
                """;

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(cypher, parameters(
                        "userId", userId,
                        "slotKey", slotKey
                ));
                if (!result.hasNext()) {
                    return Optional.empty();
                }
                Record record = result.next();
                if (record.get("lastSwitchAt").isNull()) {
                    return Optional.empty();
                }
                return Optional.of(
                        record.get("lastSwitchAt")
                                .asZonedDateTime()
                                .toInstant()
                );
            });
        }
    }


    public void initOpposeClaim(String userId, ExtractedRelation rel) {
        String claimKey = keyCodec.buildExtractRelKey(rel);

        String cypher = """
                MERGE (u:User {id:$uid})
                
                MERGE (c:Claim {
                    subjectId:$sid,
                    predicate:$pred,
                    objectId:$oid,
                    quantifier:$q,
                    polarity:$pol
                })
                ON CREATE SET
                    c.claimKey = $claimKey,
                    c.confidence = $conf,
                    c.epistemicStatus = "HYPOTHETICAL",
                    c.supportCount = 0,
                    c.priority = $pri,
                    c.source ="USER_STATEMENT",
                    c.updateAt = datetime(),
                    c.generation ="v3",
                    c.lastStatusChangedAt = datetime()
                
                MERGE (u)-[:ASSERTS]->(c)
                """;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, parameters(
                        "uid", userId,
                        "claimKey", claimKey,
                        "sid", rel.subjectId(),
                        "pred", rel.predicateType().name(),
                        "oid", rel.objectId(),
                        "q", rel.quantifier().name(),
                        "pol", rel.polarity(),
                        "conf", 0.6,
                        "pri", 0
                )).consume();
                return null;
            });
        }
    }


}
