package com.yef.agent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Slf4j
@Component
public class UserPersonaAdvisor implements CallAroundAdvisor {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    @Qualifier("milvusVectorStore")
    private VectorStore vectorStore;

    @Override
    public AdvisedResponse aroundCall(
            AdvisedRequest advisedRequest,
            CallAroundAdvisorChain chain
    ) {

        String userText = advisedRequest.userText();

        /* =========================
         * 1️⃣ 抽取 & 存储【长期事实】
         * ========================= */
        FactExtractionResult factResult = extractFacts(userText);
        if(!isHypotheticalOrMeta(userText)){
            List<String> newFacts = new ArrayList<>();
            if (factResult.hasFacts() && "high".equals(factResult.confidence())) {
                for (String fact : factResult.facts()) {
                    vectorStore.add(List.of(
                            new Document(fact, Map.of(
                                    "category", "user_profile",
                                    "confidence", "high",
                                    "source", "user_statement"
                            ))
                    ));
                    newFacts.add(fact);
                }
            }
            /* =========================
             * 2️⃣ 抽取 & 存储【计划】
             * ========================= */
            PlanMemoryItem planItem = extractPlan(userText);
            if (planItem.isHasPlan()) {
                for (PlanMemoryItem.Plan plan : planItem.getPlans()) {
                    vectorStore.add(List.of(
                            new Document(plan.getContent(), Map.of(
                                    "category", "user_plan",
                                    "planType", plan.getPlanType(),
                                    "planRole", plan.getPlanRole(),
                                    "confidence", planItem.getConfidence(),
                                    "source", "user_statement",
                                    "timestamp", System.currentTimeMillis()
                            ))
                    ));
                }
            }

            /* =========================
             * 3️⃣ 从 Milvus 召回【历史记忆】
             * ========================= */
            List<Document> memories;
            try {
                memories = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(userText)
                                .topK(6)
                                .similarityThreshold(0.4)
                                .build()
                );
            } catch (Exception e) {
                log.warn("Milvus 搜索失败，使用空记忆", e);
                memories = List.of();
            }

            String historicalText = memories.isEmpty()
                    ? "（无）"
                    : memories.stream()
                    .map(Document::getText)
                    .distinct()
                    .collect(Collectors.joining("\n"));

            /* =========================
             * 4️⃣ 构造 System Prompt（关键）
             * ========================= */
            String enhancedSystem =
                    advisedRequest.systemText()
                            + "\n\n【长期记忆（已确认）】\n"
                            + historicalText
                            + "\n\n【本轮新信息（尚未进入长期记忆）】\n"
                            + (newFacts.isEmpty() ? "（无）"
                            : newFacts.stream().map(f -> "- " + f).collect(Collectors.joining("\n")))
                            + """
                        
                        【使用规则】
                        1. 长期记忆只能用于确认已知事实
                        2. 本轮新信息只能用于当前回答
                        3. 若使用本轮信息，必须说“我刚记住你说的…”
                        4. 禁止将本轮信息当作历史事实
                        5. 禁止编造用户未明确说过的信息
                        【对话风格要求】
                        1.  如果用户是在假设、举例或试探，不要进行事实判断
                        2. 不要解释“你是 AI / 无法验证现实世界”
                        3. 只需指出这是一个假设语境，并自然回应
                        4. 语气应像日常聊天，而不是说明文
                        """;

            AdvisedRequest enhancedRequest = AdvisedRequest.from(advisedRequest)
                    .systemText(enhancedSystem)
                    .build();
            return chain.nextAroundCall(enhancedRequest);
        }
        return chain.nextAroundCall(advisedRequest);
    }

    /* =========================
     * 抽取事实
     * ========================= */
    private FactExtractionResult extractFacts(String userText) {
        return chatClient.prompt()
                .system("""
                        你是一个【事实抽取器】。
                        只提取用户明确陈述的个人事实。
                        严禁推测、总结或编造。
                        只返回 JSON。
                        """)
                .user(userText)
                .call()
                .entity(FactExtractionResult.class);
    }

    /* =========================
     * 抽取计划
     * ========================= */
    private PlanMemoryItem extractPlan(String userText) {
        return chatClient.prompt()
                .system("""
                        你是一个【计划抽取器】。
                        只抽取用户明确说出的计划。
                        不得推测。
                        """)
                .user(userText)
                .call()
                .entity(PlanMemoryItem.class);
    }

    private boolean isHypotheticalOrMeta(String text) {
        return text.matches(".*(如果|假如|要是|是否|你相信|假设|比如).*");
    }



    @Override
    public String getName() {
        return "UserPersonaAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}