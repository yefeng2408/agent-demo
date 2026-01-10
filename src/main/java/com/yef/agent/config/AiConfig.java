package com.yef.agent.config;

import com.yef.agent.advisor.UserPersonaAdvisor;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfig {

    // 1. 纯净版：用于后台提取记忆，不带任何 defaultSystem 干扰
    @Bean
    @Primary // 设为 Primary，这样 Advisor 里不加 Qualifier 默认就用它
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    // 2. 医疗版：专门给 MedAgentService 使用
    @Bean
    @Qualifier("medicalChatClient")
    public ChatClient medicalChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一名专业的互联网医院全科医生...")
                .build();
    }

    // 3. 人设版：挂载 Advisor，用于和个人深度聊天
    @Bean
    @Qualifier("personalChatClient")
    public ChatClient personalChatClient(ChatClient.Builder builder, UserPersonaAdvisor personaAdvisor) {
        return builder
                .defaultAdvisors(personaAdvisor) // 全局挂载你的记忆助手
                .build();
    }

    @Bean
    public VectorStore milvusVectorStore(MilvusServiceClient milvusClient,
                                         @Qualifier("ollamaEmbeddingModel")EmbeddingModel embeddingModel) {
        // 官方推荐使用 Builder 模式，注意包名必须匹配 image_00a1fe.jpg 中的依赖
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("user_long_term_memory_768")
                .embeddingFieldName("vector")
                .indexType(IndexType.IVF_FLAT) // 显式指定索引类型
                .metricType(MetricType.COSINE) // 语义搜索常用余弦相似度
                .build();
    }

    /**
     * 让 Spring 在注入 ChatModel 时优先选 OpenAI-compatible(DeepSeek) 的那个 Bean。
     * 注意参数名必须和容器里的 Bean 名匹配：openAiChatModel
     */
    @Bean
    @Primary
    public ChatModel primaryChatModel(@Qualifier("openAiChatModel") ChatModel chatModel) {
        return chatModel;
    }

    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(
            @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        return embeddingModel;
    }



}