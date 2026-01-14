/*
package com.yef.agent.migration;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.neo4j.driver.*;
import static org.neo4j.driver.Values.parameters;

*/
/**
 * V2(MySQL belief_state + belief_evidence) -> V3(Neo4j Claim Graph)
 *
 * One-shot runner:
 *  - Run once at startup manually (or invoke from a CommandLineRunner)
 *  - After verified, delete this package.
 *//*

@Component
public class V2ToV3MigrationRunner implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final Driver neo4j;

    // ======= 控制迁移范围（白名单） =======
    // 只迁移这些 v2 proposition 前缀
    private static final Set<String> ALLOWED_PREFIX = Set.of(
            "user_name(",
            "user_birth_year(",
            "user_job(",
            "user_owns_car("
    );

    // 丢弃这些“污染型” proposition（v2 里最容易导致你说的“记忆污染”）
    private static final Set<String> DENYLIST_EXACT = Set.of(
            "user_is_backend_developer" // 没有括号，且与 user_job(...) 语义重复/冲突
    );

    // 简单否定词表（用于从 raw_text/surface 推断 polarity=false）
    private static final List<String> NEGATION_TOKENS = List.of("没有", "并没有", "不是", "不再", "不属于", "不", "从未");

    public V2ToV3MigrationRunner(JdbcTemplate jdbc, Driver neo4j) {
        this.jdbc = jdbc;
        this.neo4j = neo4j;
    }

    */
/**
     * 手动调用：建议你临时加一个 CommandLineRunner 触发，
     * 跑完确认无误就删掉触发器和本类。
     *//*

    public void runOnce(String migrationBatchId) {
        Objects.requireNonNull(migrationBatchId);

        // 1) 读取 v2：belief_state + latest evidence（用于判定 polarity）
        List<V2Row> rows = loadV2Rows();

        // 2) 逐条 normalize -> 写入 Neo4j
        int total = 0, migrated = 0, skipped = 0;
        try (Session session = neo4j.session(SessionConfig.forDatabase("neo4j"))) {
            for (V2Row row : rows) {
                total++;

                Optional<ClaimSeed> seedOpt = normalize(row);
                if (seedOpt.isEmpty()) {
                    skipped++;
                    continue;
                }

                ClaimSeed seed = seedOpt.get();

                // 写入 Neo4j（建议每条一个事务，数据量小更安全）
                session.executeWrite(tx -> {
                    writeClaim(tx, seed);
                    writeEvidence(tx, seed, row);
                    return null;
                });

                migrated++;
            }
        }

        System.out.printf(
                "[V2->V3] batch=%s total=%d migrated=%d skipped=%d%n",
                migrationBatchId, total, migrated, skipped
        );
    }

    // ---------------------------------------------------------------------
    // Step A: Load from MySQL
    // ---------------------------------------------------------------------

    */
/**
     * 拉取 belief_state + 每条 belief 的最新 evidence（MySQL 8 window function）
     *//*

    private List<V2Row> loadV2Rows() {
        String sql = """
            SELECT
              bs.id              AS belief_id,
              bs.user_id         AS user_id,
              bs.proposition     AS proposition,
              bs.surface         AS surface,
              bs.epistemic_status AS epistemic_status,
              bs.confidence      AS belief_confidence,
              bs.updated_at      AS belief_updated_at,
              bs.created_at      AS belief_created_at,

              le.evidence_type   AS evidence_type,
              le.modality        AS modality,
              le.raw_text        AS raw_text,
              le.confidence      AS evidence_confidence,
              le.created_at      AS evidence_created_at

            FROM belief_state bs
            LEFT JOIN (
                SELECT *
                FROM (
                    SELECT
                      be.*,
                      ROW_NUMBER() OVER (PARTITION BY be.belief_id ORDER BY be.created_at DESC, be.id DESC) AS rn
                    FROM belief_evidence be
                ) t
                WHERE t.rn = 1
            ) le
            ON le.belief_id = bs.id
            ORDER BY bs.user_id, bs.id
            """;

        return jdbc.query(sql, (rs, i) -> mapV2Row(rs));
    }

    private V2Row mapV2Row(ResultSet rs) throws java.sql.SQLException {
        return new V2Row(
                rs.getLong("belief_id"),
                rs.getString("user_id"),
                rs.getString("proposition"),
                rs.getString("surface"),
                rs.getString("epistemic_status"),
                rs.getDouble("belief_confidence"),
                rs.getTimestamp("belief_updated_at") == null ? null : rs.getTimestamp("belief_updated_at").toInstant(),
                rs.getTimestamp("belief_created_at") == null ? null : rs.getTimestamp("belief_created_at").toInstant(),

                rs.getString("evidence_type"),
                rs.getString("modality"),
                rs.getString("raw_text"),
                rs.getObject("evidence_confidence") == null ? null : rs.getDouble("evidence_confidence"),
                rs.getTimestamp("evidence_created_at") == null ? null : rs.getTimestamp("evidence_created_at").toInstant()
        );
    }

    // ---------------------------------------------------------------------
    // Step B: Normalize (the "constitution")
    // ---------------------------------------------------------------------

    private Optional<ClaimSeed> normalize(V2Row row) {
        String prop = safe(row.proposition);
        if (prop.isBlank()) return Optional.empty();

        // 0) denylist
        if (DENYLIST_EXACT.contains(prop)) return Optional.empty();

        // 1) whitelist by prefix
        boolean allowed = ALLOWED_PREFIX.stream().anyMatch(prop::startsWith);
        if (!allowed) return Optional.empty();

        // 2) parse proposition: name(arg)
        ParsedProposition parsed = PropositionParser.parse(prop);
        if (parsed == null) return Optional.empty();

        // 3) choose canonical text for polarity inference:
        //    优先 raw_text（更接近真实语义），否则 surface
        String text = firstNonBlank(row.rawText, row.surface);

        // 4) infer polarity (true=肯定, false=否定)
        //    - 先看 modality / evidence_type
        //    - 再用 negation tokens 兜底
        boolean polarity = inferPolarity(row, text);

        // 5) map by proposition kind
        String userId = safe(row.userId);
        if (userId.isBlank()) return Optional.empty();

        Instant ts = row.evidenceCreatedAt != null ? row.evidenceCreatedAt
                : (row.beliefUpdatedAt != null ? row.beliefUpdatedAt : Instant.now());

        // 注意：v3 的 confidence 我建议优先用 evidence_confidence，其次 belief_confidence
        double confidence = row.evidenceConfidence != null ? row.evidenceConfidence : row.beliefConfidence;
        confidence = clamp01(confidence);

        // ---- a) user_name(x) => (User)-[NAME]->(Concept{Name:x}) polarity=true
        if (parsed.name.equals("user_name")) {
            if (parsed.args.size() != 1) return Optional.empty();
            String name = parsed.args.get(0);

            return Optional.of(ClaimSeed.builder()
                    .subjectUserId(userId)
                    .predicate("NAME")
                    .objectType(ObjectType.CONCEPT)
                    .objectId("NAME:" + name)
                    .objectDisplay(name)
                    .quantifier(Quantifier.ONE)
                    .polarity(true) // name 这种直接认为 true
                    .confidence(confidence)
                    .source("v2_migration")
                    .migrationBatchId(row.migrationBatchIdOr("manual"))
                    .eventTime(ts)
                    .build());
        }

        // ---- b) user_birth_year(1992) => BORN_YEAR -> Concept{YEAR:1992}
        if (parsed.name.equals("user_birth_year")) {
            if (parsed.args.size() != 1) return Optional.empty();
            String year = parsed.args.get(0);

            return Optional.of(ClaimSeed.builder()
                    .subjectUserId(userId)
                    .predicate("BORN_YEAR")
                    .objectType(ObjectType.CONCEPT)
                    .objectId("YEAR:" + year)
                    .objectDisplay(year)
                    .quantifier(Quantifier.ONE)
                    .polarity(true)
                    .confidence(confidence)
                    .source("v2_migration")
                    .migrationBatchId(row.migrationBatchIdOr("manual"))
                    .eventTime(ts)
                    .build());
        }

        // ---- c) user_job(backend_developer) => HAS_ROLE -> Role{BackendDeveloper} polarity derived
        if (parsed.name.equals("user_job")) {
            if (parsed.args.size() != 1) return Optional.empty();
            String roleRaw = parsed.args.get(0);

            String roleCanonical = RoleCanonicalizer.toRoleName(roleRaw); // backend_developer -> BackendDeveloper

            return Optional.of(ClaimSeed.builder()
                    .subjectUserId(userId)
                    .predicate("HAS_ROLE")
                    .objectType(ObjectType.ROLE)
                    .objectId("ROLE:" + roleCanonical)
                    .objectDisplay(roleCanonical)
                    .quantifier(Quantifier.ONE)
                    .polarity(polarity) // ✅ 由 evidence 文本/deny 推断
                    .confidence(confidence)
                    .source("v2_migration")
                    .migrationBatchId(row.migrationBatchIdOr("manual"))
                    .eventTime(ts)
                    .build());
        }

        // ---- d) user_owns_car(any|Tesla|xxx) => OWNS + quantifier
        if (parsed.name.equals("user_owns_car")) {
            if (parsed.args.size() != 1) return Optional.empty();
            String arg = parsed.args.get(0);

            // any: object=Concept(CAR), quantifier=ANY
            if ("any".equalsIgnoreCase(arg)) {
                return Optional.of(ClaimSeed.builder()
                        .subjectUserId(userId)
                        .predicate("OWNS")
                        .objectType(ObjectType.CONCEPT)
                        .objectId("CAR:any") // 只是 objectId 的稳定键
                        .objectDisplay("CAR")
                        .quantifier(Quantifier.ANY)
                        .polarity(polarity) // “没有任何车” => polarity=false（由 inferPolarity 决定）
                        .confidence(confidence)
                        .source("v2_migration")
                        .migrationBatchId(row.migrationBatchIdOr("manual"))
                        .eventTime(ts)
                        .build());
            }

            // specific brand: object=Brand(Tesla), quantifier=ONE
            String brandCanonical = BrandCanonicalizer.toBrandName(arg); // Tesla -> Tesla

            return Optional.of(ClaimSeed.builder()
                    .subjectUserId(userId)
                    .predicate("OWNS")
                    .objectType(ObjectType.BRAND)
                    .objectId("BRAND:" + brandCanonical)
                    .objectDisplay(brandCanonical)
                    .quantifier(Quantifier.ONE)
                    .polarity(polarity) // “没有特斯拉” => false
                    .confidence(confidence)
                    .source("v2_migration")
                    .migrationBatchId(row.migrationBatchIdOr("manual"))
                    .eventTime(ts)
                    .build());
        }

        return Optional.empty();
    }

    private boolean inferPolarity(V2Row row, String text) {
        // 1) modality 优先
        String modality = safe(row.modality);
        if ("deny".equalsIgnoreCase(modality)) return false;
        if ("assert".equalsIgnoreCase(modality)) return true;

        // 2) evidence_type 次之（你表里 CONFIRMED / DENIED）
        String et = safe(row.evidenceType);
        if ("DENIED".equalsIgnoreCase(et)) return false;
        if ("CONFIRMED".equalsIgnoreCase(et)) return true;

        // 3) 最后用文本否定词兜底（避免 v2 状态机写反）
        if (containsNegation(text)) return false;

        // 4) fallback：用 v2 epistemic_status（不可靠，仅兜底）
        String s = safe(row.epistemicStatus);
        if ("DENIED".equalsIgnoreCase(s)) return false;
        if ("CONFIRMED".equalsIgnoreCase(s)) return true;

        return true; // 默认偏真（也可以改成 Optional.empty 直接不迁）
    }

    private boolean containsNegation(String text) {
        String t = safe(text);
        if (t.isBlank()) return false;
        // 注意：这里很粗，但用于迁移足够，Step3 之后会被严格协议替代
        for (String token : NEGATION_TOKENS) {
            if (t.contains(token)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Step C: Write to Neo4j
    // ---------------------------------------------------------------------

    private void writeClaim(TransactionContext tx, ClaimSeed seed) {
        // 1) MERGE User
        tx.run("""
            MERGE (u:User {id: $userId})
            """, parameters("userId", seed.subjectUserId));

        // 2) MERGE Predicate
        tx.run("""
            MERGE (p:Predicate {name: $pred})
            """, parameters("pred", seed.predicate));

        // 3) MERGE Object (Concept/Brand/Role)
        switch (seed.objectType) {
            case CONCEPT -> tx.run("""
                MERGE (o:Concept {id: $oid})
                ON CREATE SET o.name = $display
                """, parameters("oid", seed.objectId, "display", seed.objectDisplay));
            case BRAND -> tx.run("""
                MERGE (o:Brand {id: $oid})
                ON CREATE SET o.name = $display
                """, parameters("oid", seed.objectId, "display", seed.objectDisplay));
            case ROLE -> tx.run("""
                MERGE (o:Role {id: $oid})
                ON CREATE SET o.name = $display
                """, parameters("oid", seed.objectId, "display", seed.objectDisplay));
        }

        // 4) MERGE Claim (稳定 key：subject+predicate+object+quantifier)
        //    ⚠️ polarity/confidence/source 允许更新（迁移可能重复跑）
        tx.run("""
                    MERGE (c:Claim {
                      subjectId: $userId,
                      predicate: $pred,
                      objectId: $oid,
                      quantifier: $quant
                    })
                    ON CREATE SET
                      c.createdAt = datetime(),
                      c.source = $source,
                      c.batch = $batch
                    SET
                      c.polarity = $polarity,
                      c.confidence = $confidence,
                      c.updatedAt = datetime()
                    """, parameters(
                                    "userId", seed.subjectUserId,
                                    "pred", seed.predicate,
                                    "oid", seed.objectId,
                                    "quant", seed.quantifier.name(),
                                    "polarity", seed.polarity,
                                    "confidence", seed.confidence,
                                    "source", "v2",
                                    "batch", seed.migrationBatchId
                            ));

      */
/*  tx.run("""
            MERGE (c:Claim {
              subjectId: $userId,
              predicate: $pred,
              objectId:  $oid,
              quantifier: $q
            })
            SET
              c.polarity = $pol,
              c.confidence = $conf,
              c.source = $source,
              c.migrationBatchId = $batch,
              c.updatedAt = datetime($ts)
            ON CREATE SET
              c.createdAt = datetime($ts)
            """,
                parameters(
                        "userId", seed.subjectUserId,
                        "pred", seed.predicate,
                        "oid", seed.objectId,
                        "q", seed.quantifier.name(),
                        "pol", seed.polarity,
                        "conf", seed.confidence,
                        "source", seed.source,
                        "batch", seed.migrationBatchId,
                        "ts", seed.eventTime.toString()
                )
        );*//*


        // 5) Connect graph
        tx.run("""
            MATCH (u:User {id: $userId})
            MATCH (c:Claim {subjectId: $userId, predicate: $pred, objectId: $oid, quantifier: $q})
            MATCH (p:Predicate {name: $pred})
            MERGE (u)-[:HAS_CLAIM]->(c)
            MERGE (c)-[:PREDICATE]->(p)
            """, parameters(
                "userId", seed.subjectUserId,
                "pred", seed.predicate,
                "oid", seed.objectId,
                "q", seed.quantifier.name()
        ));

        // object relationship
        String objLabel = switch (seed.objectType) {
            case CONCEPT -> "Concept";
            case BRAND -> "Brand";
            case ROLE -> "Role";
        };
        tx.run("""
            MATCH (c:Claim {subjectId: $userId, predicate: $pred, objectId: $oid, quantifier: $q})
            MATCH (o:%s {id: $oid})
            MERGE (c)-[:OBJECT]->(o)
            """.formatted(objLabel),
                parameters(
                        "userId", seed.subjectUserId,
                        "pred", seed.predicate,
                        "oid", seed.objectId,
                        "q", seed.quantifier.name()
                )
        );
    }

    */
/**
     * 把 v2 的最新 evidence 也写入 Neo4j（隔离区：source=v2_migration）
     * 你也可以选择不写 Evidence，这里写出来方便你可视化验证迁移正确性。
     *//*

    private void writeEvidence(TransactionContext tx, ClaimSeed seed, V2Row row) {
        String raw = firstNonBlank(row.rawText, row.surface);
        if (raw.isBlank()) return;

        String evidenceId = "v2e:" + row.beliefId + ":" + (row.evidenceCreatedAt != null ? row.evidenceCreatedAt.toEpochMilli() : Instant.now().toEpochMilli());

        tx.run("""
            MERGE (e:Evidence {id: $eid})
            SET e.rawText = $raw,
                e.modality = $modality,
                e.evidenceType = $etype,
                e.source = 'v2_migration',
                e.createdAt = datetime($ts)
            """, parameters(
                "eid", evidenceId,
                "raw", raw,
                "modality", safe(row.modality),
                "etype", safe(row.evidenceType),
                "ts", (seed.eventTime != null ? seed.eventTime : Instant.now()).toString()
        ));

        tx.run("""
            MATCH (c:Claim {subjectId: $userId, predicate: $pred, objectId: $oid, quantifier: $q})
            MATCH (e:Evidence {id: $eid})
            MERGE (c)-[:SUPPORTED_BY]->(e)
            """, parameters(
                "userId", seed.subjectUserId,
                "pred", seed.predicate,
                "oid", seed.objectId,
                "q", seed.quantifier.name(),
                "eid", evidenceId
        ));
    }

    @Override
    public void run(String... args) throws Exception {
        //param: v2
        runOnce("v2");
    }

    // ---------------------------------------------------------------------
    // Data Models
    // ---------------------------------------------------------------------

    private record V2Row(
            long beliefId,
            String userId,
            String proposition,
            String surface,
            String epistemicStatus,
            double beliefConfidence,
            Instant beliefUpdatedAt,
            Instant beliefCreatedAt,

            String evidenceType,
            String modality,
            String rawText,
            Double evidenceConfidence,
            Instant evidenceCreatedAt
    ) {
        String migrationBatchIdOr(String fallback) { return fallback; }
    }

    private enum ObjectType { CONCEPT, BRAND, ROLE }
    private enum Quantifier { ONE, ANY }

    private static final class ClaimSeed {
        final String subjectUserId;
        final String predicate;
        final ObjectType objectType;
        final String objectId;
        final String objectDisplay;
        final Quantifier quantifier;
        final boolean polarity;
        final double confidence;
        final String source;
        final String migrationBatchId;
        final Instant eventTime;

        private ClaimSeed(Builder b) {
            this.subjectUserId = b.subjectUserId;
            this.predicate = b.predicate;
            this.objectType = b.objectType;
            this.objectId = b.objectId;
            this.objectDisplay = b.objectDisplay;
            this.quantifier = b.quantifier;
            this.polarity = b.polarity;
            this.confidence = b.confidence;
            this.source = b.source;
            this.migrationBatchId = b.migrationBatchId;
            this.eventTime = b.eventTime;
        }

        static Builder builder() { return new Builder(); }

        static final class Builder {
            String subjectUserId;
            String predicate;
            ObjectType objectType;
            String objectId;
            String objectDisplay;
            Quantifier quantifier = Quantifier.ONE;
            boolean polarity = true;
            double confidence = 0.5;
            String source = "v2_migration";
            String migrationBatchId = "manual";
            Instant eventTime = Instant.now();

            Builder subjectUserId(String v) { this.subjectUserId = v; return this; }
            Builder predicate(String v) { this.predicate = v; return this; }
            Builder objectType(ObjectType v) { this.objectType = v; return this; }
            Builder objectId(String v) { this.objectId = v; return this; }
            Builder objectDisplay(String v) { this.objectDisplay = v; return this; }
            Builder quantifier(Quantifier v) { this.quantifier = v; return this; }
            Builder polarity(boolean v) { this.polarity = v; return this; }
            Builder confidence(double v) { this.confidence = v; return this; }
            Builder source(String v) { this.source = v; return this; }
            Builder migrationBatchId(String v) { this.migrationBatchId = v; return this; }
            Builder eventTime(Instant v) { this.eventTime = v; return this; }

            ClaimSeed build() {
                return new ClaimSeed(this);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers: Proposition parsing + canonicalizers
    // ---------------------------------------------------------------------

    private static final class ParsedProposition {
        final String name;        // e.g. user_job
        final List<String> args;  // e.g. [backend_developer]
        ParsedProposition(String name, List<String> args) {
            this.name = name;
            this.args = args;
        }
    }

    private static final class PropositionParser {
        // 支持：name(arg) / name(arg1,arg2)
        private static final Pattern P = Pattern.compile("^([a-zA-Z0-9_]+)\\((.*)\\)$");

        static ParsedProposition parse(String proposition) {
            String p = safe(proposition);
            Matcher m = P.matcher(p);
            if (!m.matches()) return null;

            String name = m.group(1);
            String inside = m.group(2).trim();
            if (inside.isEmpty()) return new ParsedProposition(name, List.of());

            // 简单 split：你的 arg 目前不会包含逗号嵌套，足够
            String[] parts = inside.split("\\s*,\\s*");
            List<String> args = new ArrayList<>();
            for (String part : parts) {
                args.add(part.trim());
            }
            return new ParsedProposition(name, args);
        }
    }

    private static final class RoleCanonicalizer {
        static String toRoleName(String raw) {
            // backend_developer => BackendDeveloper
            String r = safe(raw);
            if (r.isBlank()) return r;
            String[] parts = r.split("[_\\-]");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (p.isBlank()) continue;
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
            return sb.toString();
        }
    }

    private static final class BrandCanonicalizer {
        static String toBrandName(String raw) {
            String b = safe(raw);
            if (b.isBlank()) return b;
            // Tesla / tesla => Tesla
            return Character.toUpperCase(b.charAt(0)) + b.substring(1);
        }
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static String firstNonBlank(String... ss) {
        for (String s : ss) {
            if (s != null && !s.trim().isBlank()) return s.trim();
        }
        return "";
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}*/
