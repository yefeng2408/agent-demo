package com.yef.agent.advisor;

import com.yef.agent.memory.*;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class UserPersonaAdvisor {

    private final EmbeddingModel embeddingModel;
    private final ClaimExtractor claimExtractor;
    private final BeliefStore beliefStore;
    private final EpistemicStateMachine stateMachine;
    private final ConfidenceUpdater confidenceUpdater;
    private final VectorStore milvusVectorStore;
    private final MilvusServiceClient  milvusServiceClient;
    private final JdbcTemplate jdbcTemplate;
    public UserPersonaAdvisor(
            EmbeddingModel embeddingModel,
            ClaimExtractor claimExtractor,
            BeliefStore beliefStore,
            EpistemicStateMachine stateMachine,
            ConfidenceUpdater confidenceUpdater,
            VectorStore vectorStore,
            MilvusServiceClient milvusServiceClient,
            JdbcTemplate jdbcTemplate
    ) {
        this.embeddingModel = embeddingModel;
        this.claimExtractor = claimExtractor;
        this.beliefStore = beliefStore;
        this.stateMachine = stateMachine;
        this.confidenceUpdater = confidenceUpdater;
        this.milvusVectorStore = vectorStore;
        this.milvusServiceClient = milvusServiceClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 对外入口：在你原来 Advisor 的 afterResponse / advise 里调用 */
    public void onTurn(String userId, String userText, String aiText) {
        log.info("[Persona] onTurn userId={}, userText={}, aiText={}",
                userId, userText, aiText);
        // 1) LLM 抽取 “认知主张（belief claim）”
        ClaimExtractionResult extracted = claimExtractor.extract(userText, aiText);
        if (extracted == null || !extracted.hasClaims() || extracted.claims() == null) return;

        // 2) 逐条处理（这一步就是你要的“认知管理完整些”）
        for (ClaimExtractionResult.Claim claim : extracted.claims()) {
            handleSingleClaim(userId, claim, userText);
        }


    }

    private void handleSingleClaim(String userId,
                                   ClaimExtractionResult.Claim claim,
                                   String userText) {

        // --- 基础校验 ---
        if (claim.proposition() == null || claim.proposition().isBlank()) return;
        if (claim.status() == null || claim.status() == EpistemicStatus.UNKNOWN) return;

        // 3) 查旧认知（belief）
        Optional<BeliefStore.BeliefRow> existingOpt =
                beliefStore.findByUserAndProposition(userId, claim.proposition());

        EpistemicStatus fromStatus = existingOpt
                .map(row -> {
                    try {
                        return EpistemicStatus.valueOf(row.status());
                    } catch (Exception e) {
                        return EpistemicStatus.UNKNOWN;
                    }
                })
                .orElse(EpistemicStatus.UNKNOWN);
        double currentConfidence = existingOpt
                .map(BeliefStore.BeliefRow::confidence)
                .orElse(0.5);

        // 4) 构造状态迁移上下文（你之后可以把 recentEvidenceCount 从 DB 算出来）
        StatusTransitionContext ctx = new StatusTransitionContext(
                claim.proposition(),
                fromStatus,
                claim.status(),
                claim.confidence(),
                1
        );

        // 5) 是否允许迁移（状态机裁决）
        if (!stateMachine.canTransition(ctx)) {
            // 不迁移：但你仍然可以记录 evidence（可选）
            beliefStore.insertEvidence(
                    existingOpt.get().id(),
                    userId,
                    claim.status().name(),    // evidenceType
                    claim.modality(),
                    userText,
                    claim.confidence()
            );
            return;
        }

        // 6) 计算新 confidence（ConfidenceUpdater 决策，不放 Controller）
        double newConfidence = confidenceUpdater.apply(
                currentConfidence,
                fromStatus,
                claim.status(),
                claim.confidence()
        );

        // 7) upsert belief（只保留“当前认知状态”一条主记录，避免你截图那种重复）
        BeliefStore.BeliefRow row = beliefStore.upsertBelief(
                userId,
                claim.proposition(),
                claim.surface(),
                claim.status().name(),
                newConfidence
        );

        // 8) 记录 evidence（审计、回溯、未来做 hysteresis/回滚/撤销都靠它）
        beliefStore.insertEvidence(
                row.id(),                     // beliefId
                userId,                       // userId
                claim.status().name(),         // evidenceType（CONFIRMED / DENIED）
                claim.modality(),              // modality（assert / deny / hypothetical）
                userText,                     // rawText（原始对话证据）
                claim.confidence()             // confidence
        );
        String memoryText = String.format(
                "用户%s：%s（置信度 %.2f）",
                claim.status() == EpistemicStatus.CONFIRMED ? "确认" : "否认",
                claim.surface(),
                newConfidence
        );

        writeToMilvus(
                milvusServiceClient,
                embeddingModel,
                memoryText,
                userId,
                row.id(),
                row.status().toString(),
                newConfidence
        );

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