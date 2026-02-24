package com.yef.agent.repository.impl;

import com.alibaba.fastjson.JSON;
import com.yef.agent.component.KeyCodec;
import com.yef.agent.graph.answer.BeliefState;
import com.yef.agent.memory.ClaimDelta;
import com.yef.agent.memory.DeltaDirection;
import com.yef.agent.memory.EpistemicStatus;
import com.yef.agent.memory.momentum.BeliefStoreGateway;
import com.yef.agent.memory.pipeline.TransitionReason;
import com.yef.agent.memory.vo.OverrideEdge;
import com.yef.agent.repository.StatusTransitionRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.neo4j.driver.Values.parameters;

@Repository
public class StatusTransitionRepositoryImpl implements StatusTransitionRepository {

    private final Driver driver;
    private final KeyCodec keyCodec;
    private final Neo4jClient neo4jClient;

    public StatusTransitionRepositoryImpl(Driver driver, KeyCodec keyCodec, Neo4jClient neo4jClient) {
        this.driver = driver;
        this.keyCodec = keyCodec;
        this.neo4jClient = neo4jClient;
    }


    @Override
    public boolean existsOverrideTransition(String userId, String fromClaimKey, String toClaimKey) {
        String cypher = """
                MATCH (t:StatusTransition {
                    userId:$userId,
                    fromClaimKey:$fromKey,
                    toClaimKey:$toKey,
                    reason:'SELF_CORRECTION_OVERRIDE'
                })
                RETURN count(t) AS cnt
                """;
        return false;
    }

    @Override
    public boolean existsOverride(String userId,
                                  String slotKey,
                                  String fromClaimKey,
                                  String toClaimKey) {

        String cypher = """
                    MATCH (:User {id:$userId})
                          -[:HAS_CLAIM]->
                          (a:ClaimEvidence {evidenceKey:$fromKey, slotKey:$slotKey})
                          -[:OVERRIDDEN_BY]->
                          (b:ClaimEvidence {evidenceKey:$toKey})
                    RETURN count(*) AS cnt
                """;

        Map<String, Object> params = Map.of(
                "userId", userId,
                "slotKey", slotKey,
                "fromKey", fromClaimKey,
                "toKey", toClaimKey
        );

        return neo4jClient.query(cypher)
                .bindAll(params)
                .fetchAs(Long.class)
                .one()
                .map(c -> c > 0)
                .orElse(false);
    }


    @Override
    public List<OverrideEdge> loadOverrideChain(String userId, String slotKey, int limit) {

        String cypher = """
                MATCH (u:User {id:$userId})
                MATCH (u)-[:HAS_CLAIM]->(a:ClaimEvidence {slotKey:$slotKey})
                      -[:OVERRIDDEN_BY]->(b:ClaimEvidence)
                OPTIONAL MATCH (a)-[:OVERRIDE_FROM]->(t:StatusTransition)-[:OVERRIDE_TO]->(b)
                RETURN 
                    a.evidenceKey AS fromKey,
                    b.evidenceKey AS toKey,
                    coalesce(t.reason,'SELF_CORRECTION_OVERRIDE') AS reason,
                    coalesce(t.intentConfidence,0.0) AS intentConfidence,
                    toString(coalesce(t.createdAt,datetime())) AS createdAt
                ORDER BY createdAt DESC
                LIMIT $limit
                """;

        Map<String, Object> params = Map.of(
                "userId", userId,
                "slotKey", slotKey,
                "limit", limit
        );

        return (List<OverrideEdge>) neo4jClient.query(cypher)
                .bindAll(params)
                .fetchAs(OverrideEdge.class)
                .mappedBy((typeSystem, record) -> new OverrideEdge(
                        record.get("fromKey").asString(),
                        record.get("toKey").asString(),
                        record.get("reason").asString(),
                        record.get("intentConfidence").asDouble(),
                        record.get("createdAt").asString()
                ))
                .all();
    }

    @Override
    public void writeOverrideTransition(String userId,
                                        String slotKey,
                                        String fromClaimKey,
                                        String toClaimKey,
                                        double intentConfidence,
                                        ClaimDelta delta) {

        String cypher = """
                MATCH (u:User {id:$userId})
                MATCH (u)-[:HAS_CLAIM]->(a:ClaimEvidence {evidenceKey:$fromKey})
                MATCH (u)-[:HAS_CLAIM]->(b:ClaimEvidence {evidenceKey:$toKey})
                
                MERGE (a)-[r:OVERRIDDEN_BY]->(b)
                ON CREATE SET
                    r.intentConfidence = $intentConfidence,
                    r.reason = 'SELF_CORRECTION_OVERRIDE',
                    r.createdAt = datetime()
                
                MERGE (t:StatusTransition {
                    userId:$userId,
                    fromClaimKey:$fromKey,
                    toClaimKey:$toKey,
                    reason:'SELF_CORRECTION_OVERRIDE'
                })
                ON CREATE SET
                    t.id = randomUUID(),
                    t.at = datetime()
                """;

        Map<String, Object> params = new java.util.HashMap<>();
        params.put("userId", userId);
        params.put("slotKey", slotKey);
        params.put("fromKey", fromClaimKey);
        params.put("toKey", toClaimKey);
        params.put("intentConfidence", intentConfidence);

        neo4jClient.query(cypher).bindAll(params).run();
    }


    //写状态迁移记录节点
    @Override
    public void writeStatusTransition(String userId,
                                      String claimKey,
                                      EpistemicStatus from,
                                      EpistemicStatus to,
                                      ClaimDelta delta,
                                      TransitionReason reason) {

        String tid = claimKey + "|" + System.currentTimeMillis();

        String cypher = """
                    MATCH (u:User {id:$uid})
                    MATCH (c:Claim {claimKey:$claimKey})
                
                    MERGE (t:StatusTransition {id:$tid})
                    SET t.userId=$uid,
                        t.claimKey=$claimKey,
                        t.from=$from,
                        t.to=$to,
                        t.beforeConf=$beforeConf,
                        t.afterConf=$afterConf,
                        t.direction=$direction,
                        t.reason=$reason,
                        t.at=datetime()
                
                    MERGE (u)-[:HAS_TRANSITION]->(t)
                    MERGE (t)-[:OF_CLAIM]->(c)
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "uid", userId,
                    "claimKey", claimKey,
                    "tid", tid,
                    "from", from.name(),
                    "to", to.name(),
                    "beforeConf", delta.beforeConfidence(),
                    "afterConf", delta.afterConfidence(),
                    "direction", delta.direction().name(),
                    "reason", reason.name()
            )).consume());
        }
    }


    /**
     * 🔥 Dominant 裁决引擎（v4 认知仲裁核心）
     *
     * 该方法用于在同一个 ClaimSlot 内，
     * 对「当前 dominant 认知」与「challenger 新声明」进行一次完整的认知博弈，
     * 并根据多维认知指标决定是否发生 dominant 切换。
     *
     * ============================================================
     * 🧠 整体流程（对应 Cypher 0️⃣~8️⃣ 阶段）
     * ============================================================
     *
     * 0️⃣ 定位 Slot
     *    根据 slotKey 找到当前认知槽位（ClaimSlot）。
     *
     * 1️⃣ 获取当前 Dominant（可能为空）
     *    - 查询 slot 当前指向的 BeliefState（curBs）
     *    - 再通过 BASED_ON 找到当前 dominant 的 Claim（curClaim）
     *
     * 2️⃣ 获取 Challenger Claim
     *    - challengerClaimKey 对应的 Claim 节点作为挑战者。
     *
     * ------------------------------------------------------------
     * ⭐ 3️⃣ BaseScore 计算（核心评分）
     * ------------------------------------------------------------
     * 为当前 dominant 和 challenger 分别计算基础得分：
     *
     *   score =
     *      0.70 * confidence           // 置信度权重最高
     *    + 0.20 * log(1+supportCount)  // 支持次数
     *    + 0.10 * source 权重          // 来源可靠度
     *    - source 惩罚项               // hypothesis / question 扣分
     *
     * 这是 v4 的基础认知强度模型。
     *
     * ------------------------------------------------------------
     * ⭐ 4️⃣ Cooldown（冷却保护）
     * ------------------------------------------------------------
     * 如果当前 dominant 存活时间 < 45 秒：
     *
     *     challenger 扣 0.12 分
     *
     * 目的：
     * 防止刚建立的认知被瞬间推翻（抗抖动）。
     *
     * ------------------------------------------------------------
     * ⭐ 5️⃣ Momentum（认知惯性）
     * ------------------------------------------------------------
     * 读取 curBs.momentumP：
     *
     *     newMomentum =
     *         0.7 * oldMomentum
     *       + 0.3 * shockDir
     *
     * shockDir：
     *     challenger stronger → +1
     *     dominant stronger   → -1
     *
     * 作用：
     * 让认知具备「惯性」，避免频繁来回切换。
     *
     * ------------------------------------------------------------
     * ⭐ 6️⃣ Self-Heal / Decay（自愈衰减）
     * ------------------------------------------------------------
     * 当前 dominant 每被挑战一次：
     *
     *     decayLevel +1
     *
     * 并降低当前得分：
     *
     *     curScore -= 0.06 * decayLevel
     *
     * 作用：
     * 防止旧认知长期霸占 dominant。
     *
     * ------------------------------------------------------------
     * ⭐ 7️⃣ Entropy（熵衰减 / 长期未激活）
     * ------------------------------------------------------------
     * 根据 idleSeconds：
     *
     *     finalCurScore -= 0.0008 * idleSeconds
     *
     * idle 越久，认知越容易被新事实替代。
     *
     * ------------------------------------------------------------
     * ⭐ 8️⃣ Winner 决策
     * ------------------------------------------------------------
     * winnerClaim =
     *     challenger > current ? challenger : current
     *
     * shouldSwitch =
     *     是否需要创建新的 BeliefState。
     *
     * ------------------------------------------------------------
     * ⭐ 9️⃣ Commit（状态提交）
     * ------------------------------------------------------------
     * 如果 shouldSwitch == true：
     *
     * ① 创建新的 BeliefState（认知现实）
     * ② BASED_ON → winnerClaim
     * ③ 当前 BeliefState → TRANSITION_TO → 新 BeliefState
     * ④ 删除旧 DOMINANT 边
     * ⑤ slot → DOMINANT → newBeliefState
     *
     * 最终实现：
     *     🔥 Dominant 的真正“认知跃迁”
     *
     * ============================================================
     *
     * 📌 参数说明：
     *
     * slotKey
     *     认知槽位 key（user + predicate + object）
     *
     * challengerClaimKey
     *     发起挑战的新 Claim
     *
     * beliefId
     *     新 BeliefState 的 id（外部生成）
     *
     * reason
     *     触发原因（如 OPPOSITION / SELF_CORRECTION 等）
     *
     * ============================================================
     *
     * 🧠 总结一句：
     *
     * 这个方法不是简单切换 dominant，
     * 而是在模拟：
     *
     *     「认知动力学 + 惯性 + 冷却 + 衰减 + 熵」
     *
     * 的完整认知决策过程。
     *
     */
    @Override
    public void arbitrateDominant(
            String slotKey,
            String challengerClaimKey,
            String beliefId,
            String reason
    ) {
        String cypher = """
                       // 0️⃣ slot
                         MATCH (slot:ClaimSlot {key:$slotKey})
                
                         // 1️⃣ current dominant (可能不存在)
                         OPTIONAL MATCH (slot)-[domRel:DOMINANT]->(curBs:BeliefState)
                         OPTIONAL MATCH (curBs)-[:BASED_ON]->(curClaim:Claim)
                
                         // 2️⃣ challenger
                         MATCH (ch:Claim {claimKey:$challengerClaimKey})
                
                         WITH slot, domRel, curBs, curClaim, ch,
                
                         // ---- base score(cur/ch) ----
                         (
                           0.70 * coalesce(curClaim.confidence,0.0)
                         + 0.20 * log(1 + coalesce(curClaim.supportCount,0))
                         + 0.10 * CASE coalesce(curClaim.source,'')
                             WHEN 'USER_STATEMENT' THEN 1.0
                             WHEN 'SYSTEM_INFERRED' THEN 0.8
                             ELSE 0.6
                           END
                         - CASE coalesce(curClaim.source,'')
                             WHEN 'HYPOTHESIS' THEN 0.12
                             WHEN 'QUESTION' THEN 0.18
                             ELSE 0.0
                           END
                         ) AS curBase,
                
                         (
                           0.70 * coalesce(ch.confidence,0.0)
                         + 0.20 * log(1 + coalesce(ch.supportCount,0))
                         + 0.10 * CASE coalesce(ch.source,'')
                             WHEN 'USER_STATEMENT' THEN 1.0
                             WHEN 'SYSTEM_INFERRED' THEN 0.8
                             ELSE 0.6
                           END
                         - CASE coalesce(ch.source,'')
                             WHEN 'HYPOTHESIS' THEN 0.12
                             WHEN 'QUESTION' THEN 0.18
                             ELSE 0.0
                           END
                         ) AS chBase
                
                         // 3️⃣ cooldown (先做 challenger 扣分)
                         WITH slot, domRel, curBs, curClaim, ch, curBase, chBase,
                         CASE
                           WHEN curBs IS NULL THEN 999999
                           ELSE duration.inSeconds(curBs.since, datetime()).seconds
                         END AS aliveSeconds
                
                         WITH slot, domRel, curBs, curClaim, ch, curBase,
                         CASE
                           WHEN aliveSeconds < 45 THEN chBase - 0.12
                           ELSE chBase
                         END AS chAfterCooldown
                
                         // 4️⃣ momentum (用 curBs.momentumP)
                         WITH slot, domRel, curBs, curClaim, ch, curBase, chAfterCooldown,
                         coalesce(curBs.momentumP, 0.0) AS oldMomentum
                
                         WITH slot, domRel, curBs, curClaim, ch, curBase, chAfterCooldown, oldMomentum,
                         CASE WHEN chAfterCooldown > curBase THEN 1.0 ELSE -1.0 END AS shockDir
                
                         WITH slot, domRel, curBs, curClaim, ch, curBase, chAfterCooldown,
                         (0.7 * oldMomentum + 0.3 * shockDir) AS newMomentum
                
                         WITH slot, domRel, curBs, curClaim, ch,
                         curBase AS curScore,
                         (chAfterCooldown + 0.08 * newMomentum) AS chScore,
                         newMomentum
                
                         // 5️⃣ decay/self-heal (给 curScore 扣衰减)
                         WITH slot, domRel, curBs, curClaim, ch, curScore, chScore, newMomentum,
                         (coalesce(curBs.decayLevel,0) + 1) AS newDecay
                
                         WITH slot, domRel, curBs, curClaim, ch, chScore, newMomentum, newDecay,
                         (curScore - 0.06 * newDecay) AS healedCurScore
                
                         // 6️⃣ entropy (idle 越久，cur 越降)
                         WITH slot, domRel, curBs, curClaim, ch, chScore, newMomentum, newDecay, healedCurScore,
                         CASE
                           WHEN curBs IS NULL THEN 0
                           ELSE duration.inSeconds(coalesce(curBs.lastActiveAt, curBs.since), datetime()).seconds
                         END AS idleSeconds
                
                         WITH slot, domRel, curBs, curClaim, ch, chScore, newMomentum, newDecay, idleSeconds,
                         (healedCurScore - 0.0008 * idleSeconds) AS finalCurScore
                
                         // 7️⃣ 决策：winner / shouldSwitch
                         WITH slot, domRel, curBs, curClaim, ch, finalCurScore, chScore, newMomentum, newDecay,
                         CASE
                           WHEN curClaim IS NULL THEN ch
                           WHEN chScore > finalCurScore THEN ch
                           ELSE curClaim
                         END AS winnerClaim,
                
                         CASE
                           WHEN curClaim IS NULL THEN true
                           WHEN chScore > finalCurScore THEN true
                           ELSE false
                         END AS shouldSwitch
                
                         // 8️⃣ commit
                         FOREACH (_ IN CASE WHEN shouldSwitch THEN [1] ELSE [] END |
                           MERGE (newBs:BeliefState {id:$beliefId})
                           ON CREATE SET
                             newBs.slotKey = $slotKey,
                             newBs.dominantClaimKey = winnerClaim.claimKey,
                             newBs.confidence = winnerClaim.confidence,
                             newBs.momentumP = newMomentum,
                             newBs.decayLevel = CASE WHEN winnerClaim = ch THEN 0 ELSE newDecay END,
                             newBs.lastChallengedAt = datetime(),
                             newBs.reason = $reason,
                             newBs.since = datetime()
                           MERGE (newBs)-[:BASED_ON]->(winnerClaim)
                
                           FOREACH (__ IN CASE WHEN curBs IS NULL THEN [] ELSE [1] END |
                             MERGE (curBs)-[t:TRANSITION_TO]->(newBs)
                             SET t.at = datetime(), t.reason = $reason
                           )
                
                           FOREACH (__ IN CASE WHEN domRel IS NULL THEN [] ELSE [1] END |
                             DELETE domRel
                           )
                
                           MERGE (slot)-[:DOMINANT]->(newBs)
                         )
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, parameters(
                    "slotKey", slotKey,
                    "challengerClaimKey", challengerClaimKey,
                    "beliefId", beliefId,
                    "reason", reason
            )).consume());
        }
    }

    // existing code...

    /**
     * ===== v3.16 Dominant Momentum Engine =====
     * 读取当前 slot 的 BeliefState
     */
    public Optional<com.yef.agent.graph.answer.BeliefState> loadBeliefState(String slotKey) {
        String cypher = """
                MATCH (:ClaimSlot {key:$slotKey})-[:CURRENT]->(b:BeliefState)
                RETURN b
                """;
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {

                var result = tx.run(cypher, parameters("slotKey", slotKey));

                if (!result.hasNext()) {
                    return Optional.empty();
                }

                var node = result.next().get("b").asNode();

                BeliefState b = new BeliefState();
                b.setId(node.elementId());
                b.setConfidence(node.get("confidence").asDouble(0.0));
                b.setDecayLevel(node.get("decayLevel").asInt(0));
                b.setDominantClaimKey(node.get("dominantClaimKey").asString(null));
                b.setMomentumP(node.get("momentumP").asDouble(0.0));
                b.setReason(node.get("reason").asString(null));
                b.setSlotKey(node.get("slotKey").asString(null));

                if (!node.get("since").isNull()) {
                    b.setSince(node.get("since").asZonedDateTime().toInstant());
                }
                if (!node.get("lastChallengedAt").isNull()) {
                    b.setLastChallengedAt(
                            node.get("lastChallengedAt").asZonedDateTime().toInstant()
                    );
                }

                return Optional.of(b);
            });
        }
    }

    /**
     * v3.16 动量更新函数（纯内存算法，不访问数据库）
     */
    public double updateMomentum(double current, DeltaDirection direction) {

        double step = 0.15;      // 每次推动强度
        double decay = 0.92;     // 自然衰减

        double next = current * decay;

        if (direction == DeltaDirection.UP) {
            next += step;
        } else if (direction == DeltaDirection.DOWN) {
            next -= step;
        }

        // clamp 到 [-1,1]
        if (next > 1.0) next = 1.0;
        if (next < -1.0) next = -1.0;

        return next;
    }

    /**
     * 写回 BeliefState 的 momentumP
     */
    public void updateBeliefMomentum(String slotKey, double momentum) {

        String cypher = """
                MATCH (b:BeliefState {slotKey:$slotKey})
                SET b.momentumP = $momentum,
                    b.updatedAt = datetime()
                """;

        try (Session session = driver.session()) {
            session.executeWrite(tx ->
                    tx.run(cypher, parameters(
                            "slotKey", slotKey,
                            "momentum", momentum
                    )).consume()
            );
        }
    }

}
