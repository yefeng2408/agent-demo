package com.yef.agent.memory.momentum;

import com.yef.agent.graph.answer.BeliefState;
import com.yef.agent.memory.EpistemicStatus;
import lombok.RequiredArgsConstructor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionWork;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.neo4j.driver.Values.parameters;

@Component
@RequiredArgsConstructor
public class BeliefStoreGateway {

    private final Driver driver;

    private final Neo4jClient neo4jClient;

    public EpistemicStatus loadDominantStatus(String slotKey) {
        String cypher = """
                    MATCH (s:ClaimSlot {key:$slotKey})-[:DOMINANT]->(b:BeliefState)
                    RETURN b.epistemicStatus AS status
                    LIMIT 1
                """;
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(cypher, parameters("slotKey", slotKey));
                if (!r.hasNext()) {
                    return null;
                }
                String raw = r.single().get("status").asString();
                return EpistemicStatus.fromGraph(raw);
            });
        }
    }


    public void linkTransition(String oldId, String newId) {

        neo4jClient.query("""
                            MATCH (old)
                            WHERE elementId(old) = $oldId
                            MATCH (new)
                            WHERE elementId(new) = $newId
                            MERGE (old)-[:TRANSITION_TO]->(new)
                        """)
                .bind(oldId).to("oldId")
                .bind(newId).to("newId")
                .run();
    }

    /**
     * 读取某个 slotKey 对应的 BeliefState（若不存在则 Optional.empty）
     */
    public Optional<BeliefState> load(String slotKey) {
        final String cypher = """
                MATCH (b:BeliefState {slotKey:$slotKey})
                RETURN
                  b.id               AS id,
                  b.slotKey          AS slotKey,
                  b.confidence       AS confidence,
                  b.decayLevel       AS decayLevel,
                  b.dominantClaimKey AS dominantClaimKey,
                  b.lastChallengedAt AS lastChallengedAt,
                  b.momentumP        AS momentumP,
                  b.reason           AS reason,
                  b.since            AS since
                LIMIT 1
                """;

        try (Session session = driver.session()) {
            return session.readTransaction((TransactionWork<Optional<BeliefState>>) tx -> {
                Result rs = tx.run(cypher, parameters("slotKey", slotKey));
                if (!rs.hasNext()) return Optional.empty();
                Record r = rs.next();
                return Optional.of(mapBeliefState(r));
            });
        }
    }


    /**
     * 确保 BeliefState 存在（如果不存在则创建一个最小节点）
     * - 你可以在 slot-init / createInitialClaimSlot 后调用
     */
    public void ensureBeliefStateExists(String slotKey) {
        final String cypher = """
                MERGE (b:BeliefState {slotKey:$slotKey})
                ON CREATE SET
                  b.id = coalesce(b.id, $id),
                  b.confidence = coalesce(b.confidence, $confidence),
                  b.decayLevel = coalesce(b.decayLevel, $decayLevel),
                  b.momentumP = coalesce(b.momentumP, $momentumP),
                  b.since = coalesce(b.since, $since),
                  b.reason = coalesce(b.reason, $reason)
                RETURN b.slotKey AS slotKey
                """;

        Map<String, Object> p = new HashMap<>();
        p.put("slotKey", slotKey);
        p.put("id", slotKey + "|" + Instant.now().toEpochMilli());
        p.put("confidence", 0.0d);
        p.put("decayLevel", 0);
        p.put("momentumP", 0.0d);
        p.put("since", Instant.now().toString());
        p.put("reason", "BOOTSTRAP");

        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                tx.run(cypher, p);
                return null;
            });
        }
    }

    /**
     * 更新 belief 的 momentum（以及可选字段）
     * 约定：传 null 的字段不更新（保持原值）
     */
    public void updateBeliefMomentum(
            String slotKey,
            Double momentumP,
            String reason,
            String dominantClaimKey,
            Instant lastChallengedAt,
            Instant since
    ) {
        // 动态 SET：只写入非 null 字段
        StringBuilder set = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        params.put("slotKey", slotKey);

        // 你可以选择：强制先 MERGE 确保存在，避免 update 没命中
        // 这里用 MERGE + SET 方式一次搞定（推荐）
        String base = "MERGE (b:BeliefState {slotKey:$slotKey})\n";

        // 如果是新建节点，也给个 id（你也可以换成 UUID）
        base += "ON CREATE SET b.id = coalesce(b.id, $id), b.since = coalesce(b.since, $createSince)\n";
        params.put("id", slotKey + "|" + Instant.now().toEpochMilli());
        params.put("createSince", Instant.now().toString());

        if (momentumP != null) {
            appendSet(set, "b.momentumP = $momentumP");
            params.put("momentumP", momentumP);
        }
        if (reason != null) {
            appendSet(set, "b.reason = $reason");
            params.put("reason", reason);
        }
        if (dominantClaimKey != null) {
            appendSet(set, "b.dominantClaimKey = $dominantClaimKey");
            params.put("dominantClaimKey", dominantClaimKey);
        }
        if (lastChallengedAt != null) {
            appendSet(set, "b.lastChallengedAt = $lastChallengedAt");
            params.put("lastChallengedAt", lastChallengedAt.toString());
        }
        if (since != null) {
            appendSet(set, "b.since = $since");
            params.put("since", since.toString());
        }

        // 如果你传的全是 null，就不需要写库
        if (set.isEmpty()) return;

        final String cypher = base +
                "SET " + set + "\n" +
                "RETURN b.slotKey AS slotKey";

        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }

    /**
     * 纯计算：根据 direction 更新 momentum（不写库）
     * - 如果你已有 DominantMomentumAccumulator.update(...)，这块你也可以不需要
     */
    public double updateMomentum(double currentMomentum, int direction) {
        // 你可以按你 v3.16 的规则替换这里
        // direction: +1 support, -1 oppose, 0 none
        double step = 0.1d;
        double next = currentMomentum + direction * step;
        if (next < 0.0d) next = 0.0d;
        if (next > 1.0d) next = 1.0d;
        return next;
    }

    // ---------------------------
    // mapping helpers
    // ---------------------------

    private BeliefState mapBeliefState(Record r) {
        BeliefState b = new BeliefState();
        // b.setId(getString(r, "id"));
        b.setSlotKey(getString(r, "slotKey"));
        b.setConfidence(getDouble(r, "confidence", 0.0d));
        b.setDecayLevel(getInt(r, "decayLevel", 0));
        b.setDominantClaimKey(getString(r, "dominantClaimKey"));
        b.setLastChallengedAt(getInstant(r, "lastChallengedAt"));
        b.setMomentumP(getDouble(r, "momentumP", 0.0d));
        b.setReason(getString(r, "reason"));
        b.setSince(getInstant(r, "since"));
        return b;
    }

    private static void appendSet(StringBuilder set, String fragment) {
        if (!set.isEmpty()) set.append(", ");
        set.append(fragment);
    }

    private static String getString(Record r, String key) {
        if (!r.containsKey(key)) return null;
        Value v = r.get(key);
        if (v == null || v.isNull()) return null;
        try {
            return v.asString();
        } catch (Exception ignore) {
            // 有时 neo4j 会把某些值当成其它类型（极少），这里兜底
            return String.valueOf(v);
        }
    }

    private static int getInt(Record r, String key, int def) {
        if (!r.containsKey(key)) return def;
        Value v = r.get(key);
        if (v == null || v.isNull()) return def;
        try {
            return v.asInt(def);
        } catch (Exception e) {
            return def;
        }
    }

    private static double getDouble(Record r, String key, double def) {
        if (!r.containsKey(key)) return def;
        Value v = r.get(key);
        if (v == null || v.isNull()) return def;
        try {
            return v.asDouble(def);
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 兼容：
     * - neo4j 里存的是 datetime/DateTime/字符串
     * - 你现在节点面板里像 "2026-02-15T07:39:30.396000000Z"
     */
    private static Instant getInstant(Record r, String key) {
        if (!r.containsKey(key)) return null;
        Value v = r.get(key);
        if (v == null || v.isNull()) return null;

        // 1) 如果就是字符串
        try {
            String s = v.asString();
            if (s == null || s.isBlank()) return null;
            return Instant.parse(s);
        } catch (Exception ignore) {
        }

        // 2) Neo4j 原生 datetime 类型（驱动可直接 asZonedDateTime）
        try {
            return v.asZonedDateTime().toInstant();
        } catch (Exception ignore) {
        }

        return null;
    }
}