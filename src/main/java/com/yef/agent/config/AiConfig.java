package com.yef.agent.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
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


    @Bean
    @Qualifier("personalChatClient")
    public ChatClient personalChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public VectorStore milvusVectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("vector_store")
                .embeddingFieldName("vector")
                .build();
    }

   /* @Bean
    public VectorStore milvusVectorStore(MilvusServiceClient milvusClient,
                                         @Qualifier("ollamaEmbeddingModel")EmbeddingModel embeddingModel) {
        // 官方推荐使用 Builder 模式，注意包名必须匹配 image_00a1fe.jpg 中的依赖
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("user_long_term_memory_768")
                .embeddingFieldName("vector")
                .indexType(IndexType.IVF_FLAT) // 显式指定索引类型
                .metricType(MetricType.COSINE) // 语义搜索常用余弦相似度
                .build();
    }*/

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost("localhost")
                        .withPort(19530)
                        .build()
        );
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