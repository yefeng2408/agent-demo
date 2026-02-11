package com.yef.agent.repository.impl;

import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.ClaimEvidence;
import com.yef.agent.graph.eum.PredicateType;
import com.yef.agent.graph.eum.Quantifier;
import com.yef.agent.graph.eum.Source;
import com.yef.agent.graph.eum.SourceAdapter;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.narrative.OverrideEvent;
import com.yef.agent.memory.vo.DominantClaimVO;
import com.yef.agent.memory.vo.OverriddenEdgeVO;
import com.yef.agent.repository.ClaimEvidenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.neo4j.driver.Values.parameters;

@Slf4j
@Component
public class ClaimEvidenceRepositoryImpl implements ClaimEvidenceRepository {

    private final Driver driver;
    private final KeyCodec keyCodec;

    public ClaimEvidenceRepositoryImpl(Driver driver, KeyCodec keyCodec) {
        this.driver = driver;
        this.keyCodec = keyCodec;
    }

    @Override
    public ClaimEvidence loadByKey(String userId, String claimKey) {
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

            ClaimEvidence claimEvidence = new ClaimEvidence(
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
                    rec.get("priority").asInt()
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
                    c.updatedAt = datetime()
                RETURN c
                """;
        try (Session session = driver.session()) {
            session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "sid", decode.subjectId(),
                            "pred", decode.predicate().name(),
                            "oid", decode.objectId(),
                            "q", decode.quantifier().name(),
                            "pol", decode.polarity(),
                            "conf", confidence
                    ))
            );
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
            session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "sid", decode.subjectId(),
                            "pred", decode.predicate().name(),
                            "oid", decode.objectId(),
                            "q", decode.quantifier().name(),
                            "pol", decode.polarity(),
                            "eStatus", epistemicStatus.name()
                    ))
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

                return new ClaimEvidence(
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
                        rec.get("priority").asInt()
                );
            }).toList();
        }
    }


    // ===== DOMINANT 相关 =====

    /**
     * 删除当前 claimSlot 上的 dominant（如果存在）
     */
    @Override
    public void clearDominant(String userId, String claimKey) {
        String cypher = """
                MATCH (slot:ClaimSlot {key:$slot})-[r:DOMINANT]->()
                DELETE r
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx ->
                    tx.run(cypher, parameters("slot", claimKey))
            );
        }
    }

    /**
     * 写入新的 dominant 语义
     */
    @Override
    public void writeDominant(String userId,
                              String claimKey,
                              String claimEvidenceId,
                              double supportConfidenceAt,
                              String reason) {

        String cypher = """
                MERGE (slot:ClaimSlot {key:$slot})
                MATCH (c:ClaimEvidence {id:$cid})
                MERGE (slot)-[r:DOMINANT]->(c)
                SET r.since = datetime(),
                    r.supportConfidenceAt = $conf,
                    r.reason = $reason
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx ->
                    tx.run(cypher, parameters(
                            "slot", claimKey,
                            "cid", claimEvidenceId,
                            "conf", supportConfidenceAt,
                            "reason", reason
                    ))
            );
        }
    }

    /**
     * 写 OVERRIDDEN_BY 路径
     */
    @Override
    public void writeOverriddenBy(String oldClaimEvidenceId,
                                  String newClaimEvidenceId,
                                  String reason) {

        String cypher = """
                MATCH (old:ClaimEvidence {id:$oldId})
                MATCH (new:ClaimEvidence {id:$newId})
                MERGE (old)-[r:OVERRIDDEN_BY]->(new)
                SET r.at = datetime(),
                    r.reason = $reason
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx ->
                    tx.run(cypher, parameters(
                            "oldId", oldClaimEvidenceId,
                            "newId", newClaimEvidenceId,
                            "reason", reason
                    ))
            );
        }
    }


    /**
     * 查询当前 dominant（回答阶段用）
     */
    @Override
    public Optional<ClaimEvidence> loadDominant(String userId, String claimKey) {
        String cypher = """
                        MATCH (u:User {id:$uid})
                                MATCH (slot:ClaimSlot {key:$slot})
                                MATCH (slot)-[:DOMINANT]->(c:ClaimEvidence)
                                RETURN
                                  c.id            AS id,
                                  c.subjectId     AS subjectId,
                                  c.predicate     AS predicate,
                                  c.objectId      AS objectId,
                                  c.quantifier    AS quantifier,
                                  c.polarity      AS polarity,
                                  c.epistemicStatus AS epistemicStatus,
                                  c.confidence    AS confidence,
                                  c.source        AS source,
                                  c.batch         AS batch,
                                  c.updatedAt     AS updatedAt,
                                  c.lastStatusChangedAt AS lastStatusChangedAt,
                                  c.priority      AS priority
                """;
        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "slot", claimKey
                    )).list().stream().findFirst()
            ).map(this::mapToClaimEvidence);
        }
    }

    @Override
    public Optional<DominantClaimVO> loadDominant2(String userId, String claimKey) {
        KeyCodec.DecodedKey k = keyCodec.decode(claimKey);
        String cypher = """
                    MATCH (u:User {id:$uid})-[:ASSERTS]->(c:Claim)
                    MATCH (c)-[d:DOMINANT]->(:Dominant)
                    WHERE
                      c.subjectId = $sid AND
                      c.predicate = $pred AND
                      c.objectId  = $oid AND
                      c.quantifier = $q
                    RETURN c, d.since AS since, d.supportConfidenceAt AS sca, d.reason AS reason
                    LIMIT 1
                """;

        try (Session s = driver.session()) {
            return s.executeRead(tx ->
                    tx.run(cypher, parameters(
                            "uid", userId,
                            "sid", k.subjectId(),
                            "pred", k.predicate().name(),
                            "oid", k.objectId(),
                            "q", k.quantifier().name()
                    )).list().stream().findFirst()
            ).map(r -> DominantClaimVO.fromRecord(r, keyCodec));
        }
    }


    private ClaimEvidence mapToClaimEvidence(Record r) {
        return new ClaimEvidence(
                r.get("subjectId").asString(),
                PredicateType.valueOf(r.get("predicate").asString()),
                r.get("objectId").asString(),
                Quantifier.valueOf(r.get("quantifier").asString()),
                r.get("polarity").asBoolean(),
                EpistemicStatus.valueOf(r.get("epistemicStatus").asString()),
                r.get("confidence").asDouble(),
                Source.valueOf(r.get("source").asString()),
                r.get("batch").isNull() ? null : r.get("batch").asString(),
                r.get("updatedAt").asZonedDateTime().toInstant(),
                r.get("lastStatusChangedAt").isNull()
                        ? null
                        : r.get("lastStatusChangedAt").asZonedDateTime().toInstant(),
                r.get("priority").asInt()
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

    @Override
    public List<OverrideEvent> loadOverrideHistory2(String claimEvidenceId) {
        String cypher = """
                    MATCH (old:Claim)-[o:OVERRIDDEN_BY]->(new:Claim)
                    WHERE new.id = $cid
                    RETURN old.id AS fromId, new.id AS toId, o.at AS at, o.reason AS reason
                    ORDER BY o.at DESC
                    LIMIT 5
                """;
        try (Session s = driver.session()) {
            return s.executeRead(tx ->
                    tx.run(cypher, parameters("cid", claimEvidenceId))
                            .list()
                            .stream()
                            .map(r -> new OverrideEvent(
                                    r.get("fromId").asString(),
                                    r.get("toId").asString(),
                                    r.get("at").asZonedDateTime().toInstant(),
                                    r.get("reason").asString()
                            ))
                            .toList()
            );
        }
    }

}
