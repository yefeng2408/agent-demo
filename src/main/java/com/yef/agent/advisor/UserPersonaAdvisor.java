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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class UserPersonaAdvisor implements CallAroundAdvisor {
    // 必须在异步块外部锁定 userId
    final String currentUserId = "yefeng_1992";
    //用于提词的chatClient
    @Autowired
    private ChatClient chatClient;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    @Qualifier("milvusVectorStore")
    VectorStore vectorStore;



    @Override
    public AdvisedResponse aroundCall(
            AdvisedRequest advisedRequest,
            CallAroundAdvisorChain chain) {

        String userText = advisedRequest.userText();

        /* ===============================
         * 1️⃣ 从 Milvus 检索长期记忆
         * =============================== */
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
            log.warn("Milvus search failed, fallback empty", e);
            memories = List.of();
        }

        String memoryContext = memories.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        /* ===============================
         * 2️   ⃣ 从当前输入中【提取事实】
         * =============================== */
        MemoryResult memoryResult = extractFacts(userText);

        if (memoryResult.hasHighConfidenceFacts()) {
            Document memoryDoc = new Document(
                    memoryResult.toMemoryText(),
                    Map.of(
                            "category", "user_profile",
                            "confidence", "high",
                            "source", "user_statement",
                            "timestamp", System.currentTimeMillis()
                    )
            );
            vectorStore.add(List.of(memoryDoc));
        }

        /* ===============================
         * 3️⃣ 强约束 system prompt
         * =============================== */
        String enhancedSystem =
                advisedRequest.systemText()
                        + "\n\n【长期记忆（真实可信）】\n"
                        + memoryContext;

        AdvisedRequest augmented =
                AdvisedRequest.from(advisedRequest)
                        .systemText(enhancedSystem)
                        .build();

        return chain.nextAroundCall(augmented);
    }

    /* ===============================
     * 事实抽取（非常关键）
     * =============================== */
    private MemoryResult extractFacts(String userText) {
        String prompt = """
                        你是一个【事实抽取器】。
                        
                        任务：
                        从用户输入中，提取【确定无疑的个人事实】。
                        
                        规则：
                        - 只提取用户明确陈述的事实
                        - 不允许推测、总结、联想
                        - 如果没有事实，返回 hasFacts=false
                        - 只有事实明确时，confidence 才是 high
                        
                        请严格按照下面格式返回（注意：不是 JSON，只是结构）：
                        
                        hasFacts: true/false
                        confidence: high/low
                        facts:
                        - 用户的名字是叶丰
                        - 用户今年33岁
                        - 用户的爱好是健身、游泳和跑步
                        
                        用户输入：
                        """ + userText;

        return chatClient.prompt()
                .system("你是一个严格的信息抽取器")
                .user(prompt)
                .call()
                .entity(MemoryResult.class);
    }

    /*@Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        // 1. 从数据库捞取你的“永久记忆” (比如：10年Java、想去日本)
        String personaContext = getPersonaFromDb(currentUserId);

        // 2. 构造新的 System Prompt，把记忆塞进去
        // 这一步就是让 AI 拥有“记得你是谁”的能力
        String enhancedSystemText = advisedRequest.systemText()
                + "\n【用户永久记忆】: " + personaContext;

        // 3. 使用 Builder 模式重建请求对象，注入增强后的 Prompt
        AdvisedRequest augmentedRequest = AdvisedRequest.from(advisedRequest)
                .systemText(enhancedSystemText)
                .build();
        // 4. 将增强后的请求传给链路中的下一个节点（或真正的 LLM）
        AdvisedResponse response = chain.nextAroundCall(augmentedRequest);
        // 异步提取：不影响当前对话响应速度
        CompletableFuture.runAsync(() -> {
            // 1. 获取用户输入的内容
            String userText = advisedRequest.userText();
            String aiText = advisedRequest.userText() + " " + response.response().getResult().getOutput().getText();
            String chatDetail = userText + " | " + aiText;
            // 核心：把对话扔给 AI，让它输出结构化的“新知识”
            List<UserAttribute> newInfo = extractNewAttributes(userText, chatDetail);
            // 存储到数据库
            if (newInfo != null && !newInfo.isEmpty()) {
                // 再次调用一个基础的 chatClient，问它：这段对话里用户提到了什么新的个人信息？
                // 然后将结果 update 到你的 MySQL user_knowledge 表里
                // 这里使用 currentUserId 才是安全的
                newInfo.forEach(attr -> updateUserKnowledge(currentUserId, attr));
            }

        });
    return response;
    }*/

   /* private String getPersonaFromDb(String userId) {
        // 模拟从数据库 user_knowledge 表查询
        // 建议明天咱们把“异步提取新信息”也写进 aroundCall 之后的逻辑里
        return "姓名叶丰,1992年生,10年Java经验,正在学Go,目标寻找日本远程Offer";
    }*/

    @Override
    public String getName() {
        return "UserPersonaAdvisor";
    }

    @Override
    public int getOrder() {
        return 0; // 优先级，0 表示最先执行
    }

    /*private List<UserAttribute> extractNewAttributes(String question, String answer) {
        return chatClient.prompt()
                .user(u -> u.text("""
                你是一个精准的信息提取专家。请分析以下对话，提取用户展现出的永久性个人属性（如：职业、技能、理想、所在地、性格倾向等）。
                
                对话内容：
                用户问：{q}
                AI答：{a}
                
                要求：
                1. 只提取事实，不要主观臆断。
                2. 如果没有新信息，返回空列表。
                3. key 必须是英文（如：job_title, goal, location）。
                """)
                        .param("q", question)
                        .param("a", answer))
                .call()
                .entity(new ParameterizedTypeReference<List<UserAttribute>>() {}); // 强转为 List
    }*/


   /* private void updateUserKnowledge(String userId, UserAttribute attr) {
        String sql = """
        INSERT INTO user_knowledge (user_id, key_concept, content) 
        VALUES (?, ?, ?) 
        ON DUPLICATE KEY UPDATE content = VALUES(content), update_time = NOW()
        """;

        try {
            jdbcTemplate.update(sql, userId, attr.key(), attr.value());
        } catch (Exception e) {
            // 异步线程里报错要记得打 log，否则很难排查
            System.err.println("更新用户记忆失败: " + e.getMessage());
        }
    }*/


}