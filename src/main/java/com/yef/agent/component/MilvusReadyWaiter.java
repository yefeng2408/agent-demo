package com.yef.agent.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.Socket;

@Slf4j
@Configuration
public class MilvusReadyWaiter {

    @Value("${milvus.host:milvus}")
    private String host;

    @Bean
    public ApplicationRunner waitMilvusRunner() {
        return args -> {

            log.info("🔥 Waiting Milvus {}:{} ...", host, 19530);

            int retry = 60;

            while (retry-- > 0) {
                try (Socket socket = new Socket(host, 19530)) {
                    log.info("✅ Milvus Ready !");
                    return;
                } catch (Exception e) {
                    Thread.sleep(2000);
                }
            }

            throw new RuntimeException("❌ Milvus not ready after retry");
        };
    }
}