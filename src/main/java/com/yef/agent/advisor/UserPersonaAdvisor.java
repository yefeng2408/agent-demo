package com.yef.agent.advisor;

import com.yef.agent.memory.*;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.yef.agent.memory.EpistemicStatus.CONFIRMED;

@Slf4j
@Component
public class UserPersonaAdvisor {

    private final EmbeddingModel embeddingModel;
    private final ClaimExtractor claimExtractor;
    private final BeliefStore beliefStore;
    private final EpistemicStateMachine stateMachine;
    private final ConfidenceUpdater confidenceUpdater;
    private final MilvusServiceClient  milvusServiceClient;
    private final JdbcTemplate jdbcTemplate;
    public UserPersonaAdvisor(
            EmbeddingModel embeddingModel,
            ClaimExtractor claimExtractor,
            BeliefStore beliefStore,
            EpistemicStateMachine stateMachine,
            ConfidenceUpdater confidenceUpdater,
            MilvusServiceClient milvusServiceClient,
            JdbcTemplate jdbcTemplate
    ) {
        this.embeddingModel = embeddingModel;
        this.claimExtractor = claimExtractor;
        this.beliefStore = beliefStore;
        this.stateMachine = stateMachine;
        this.confidenceUpdater = confidenceUpdater;
        this.milvusServiceClient = milvusServiceClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 对外入口：在你原来 Advisor 的 afterResponse / advise 里调用 */
    public void onTurn(String userId, String userText, String aiText) {
        log.info("[Persona] onTurn userId={}, userText={}, aiText={}", userId, userText, aiText);
        // 1) LLM 抽取 “认知主张（belief claim）”
        ClaimExtractionResult extracted = claimExtractor.extract(userText, aiText);
        if (extracted == null || !extracted.hasClaims() || extracted.claims() == null) return;

        // 2) 逐条处理（这一步就是我要的“认知管理完整些”）
        for (ClaimExtractionResult.Claim claim : extracted.claims()) {
            handleSingleClaim(userId, claim, userText);
            propagateConsistency(userId, claim);
        }


    }

    private void handleSingleClaim(String userId,
                                   ClaimExtractionResult.Claim claim,
                                   String userText) {

        // 0️⃣ 基础校验
        if (claim.proposition() == null || claim.proposition().isBlank()) return;
        if (claim.status() == null || claim.status() == EpistemicStatus.UNKNOWN) return;

        // 1️⃣ 查旧 belief（只会有 0 或 1 条）
        Optional<BeliefStore.BeliefRow> existingOpt =
                beliefStore.findByUserAndProposition(userId, claim.proposition());

        EpistemicStatus fromStatus = existingOpt.map(r -> EpistemicStatus.valueOf(r.status()))
                .orElse(EpistemicStatus.UNKNOWN);

        double currentConfidence = existingOpt.map(BeliefStore.BeliefRow::confidence).orElse(0.5);

        // 2️⃣ 构造状态迁移上下文
        StatusTransitionContext ctx = new StatusTransitionContext(
                claim.proposition(),
                fromStatus,
                claim.status(),
                claim.confidence(),
                1
        );

        // 3️⃣ 状态机裁决：是否允许跃迁（只影响 status）
        boolean canTransition = stateMachine.canTransition(ctx);

        // 4️⃣ 计算新 confidence（⚠️ 永远执行）
        double newConfidence = confidenceUpdater.apply(
                currentConfidence,
                fromStatus,
                claim.status(),
                claim.confidence()
        );

        // 5️⃣ 决定最终 epistemic_status
        EpistemicStatus finalStatus = canTransition ? claim.status() : fromStatus;

        // 6️⃣ 更新 belief_state（永远只保留一条）
        BeliefStore.BeliefRow row = beliefStore.upsertBelief(
                userId,
                claim.proposition(),
                claim.surface(),
                finalStatus.name(),
                newConfidence
        );

        // 7️⃣ 记录 evidence（永远记录）
        beliefStore.insertEvidence(
                row.id(),
                userId,
                claim.status().name(),
                claim.modality(),
                userText,
                claim.confidence()
        );

        // 8️⃣ 写入 Milvus（用最终状态）
        String memoryText = String.format(
                "用户%s：%s（置信度 %.2f）",
                finalStatus == CONFIRMED ? "确认" : "否认",
                claim.surface(),
                newConfidence
        );

        writeToMilvus(
                milvusServiceClient,
                embeddingModel,
                memoryText,
                userId,
                row.id(),
                finalStatus.name(),
                newConfidence
        );
    }

    /**
     * 旧认知和新认知之间的状态同步（一致性传播） 否认旧事物同时也要肯定新事物，反之同理
     * @param userId
     * @param claim
     */
    private void propagateConsistency(String userId, ClaimExtractionResult.Claim claim) {
        PredicateKey key = PredicateKey.parse(claim.proposition());

        // 情形 1：否认具体实例 ⇒ 支持 any
        if (!key.isAny() && claim.status() == EpistemicStatus.DENIED) {
            PredicateKey anyKey = key.toAny();
            beliefStore.bumpConfidence(
                    userId,
                    anyKey.toProposition(),
                    0.2,
                    EpistemicStatus.CONFIRMED
            );
        }

        // 情形 2：确认 any ⇒ 打压所有具体实例
        if (key.isAny() && claim.status() == EpistemicStatus.CONFIRMED) {
            beliefStore.downscaleAllSpecifics(
                    userId,
                    key.name(),
                    0.1
            );
        }

        // 情形 3：否认 any ⇒ 打压所有具体实例
        if (key.isAny() && claim.status() == EpistemicStatus.DENIED) {
            beliefStore.downscaleAllSpecifics(
                    userId,
                    key.name(),
                    0.0
            );
        }
    }




    public void writeToMilvus(
            MilvusServiceClient milvusClient,
            EmbeddingModel embeddingModel,
            String memoryText,
            String userId,
            Long beliefId,
            String status,
            Double confidence
    ) {
        // 1️⃣ 手动生成 embedding（你这一步一直是对的）
        float[] vector = embeddingModel.embed(memoryText);
        // 1. 将 float[] 转换为 List<Float>
        List<Float> vectorList = new ArrayList<>();
        for (float f : vector) {
            vectorList.add(f);
        }
        // 2.3.5 正确姿势：使用 withCollectionName 指定目标表
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName("vector_store") // 显式指定集合名称
                .withFields(List.of(
                        new InsertParam.Field("vector", List.of(vectorList)),
                        new InsertParam.Field("text", List.of(memoryText)),
                        new InsertParam.Field("userId", List.of(userId)),
                        new InsertParam.Field("beliefId", List.of(beliefId.toString())),
                        new InsertParam.Field("status", List.of(status)),
                        new InsertParam.Field("confidence", List.of(confidence.floatValue())),
                        new InsertParam.Field("type", List.of("belief"))
                ))
                .build();
        // 3️⃣ 插入
        R<MutationResult> result = milvusClient.insert(insertParam);
        // 4️⃣ 判断结果（2.3.5 没有 ok()）
        if (result.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException(
                    "Milvus insert failed: " + result.getStatus() + ", reason=" + result.getMessage()
            );
        }
    }

    public List<String> getUserMemories(String userId) {
        // 只取 CONFIRMED 的认知
        List<BeliefStore.BeliefRow> beliefs =
                this.findConfirmedBeliefsByUser(userId);

        if (beliefs == null || beliefs.isEmpty()) {
            return List.of();
        }
        return beliefs.stream()
                .map(b -> String.format(
                        "• %s（置信度 %.2f）",
                        b.surface(),
                        b.confidence()
                ))
                .toList();
    }

    public List<BeliefStore.BeliefRow> findConfirmedBeliefsByUser(String userId) {
        String sql = """
        SELECT id, user_id, proposition, surface, epistemic_status, confidence,updated_at, created_at
        FROM belief_state
        WHERE user_id = ?
          AND epistemic_status = 'CONFIRMED'
        ORDER BY updated_at DESC
        LIMIT 10
        """;
        return jdbcTemplate.query(sql, BeliefStore.beliefRowMapper, userId);
    }
}