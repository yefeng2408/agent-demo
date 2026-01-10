/*
package com.yef.agent.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusNativeConfig {
    @Bean
    public MilvusServiceClient milvusClient() {
        // 原生 gRPC 连接，简单粗暴，永不 404
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost("localhost")
                .withPort(19530)
                .build();
        return new MilvusServiceClient(connectParam);
    }
}
*/
