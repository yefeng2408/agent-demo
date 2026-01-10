/*
package com.yef.agent.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class DeepSeekClient {

    private final WebClient webClient;

    public DeepSeekClient(
            @Value("${deepseek.api-key}") String apiKey
    ) {
        this.webClient = WebClient.builder()
                .baseUrl("https://api.deepseek.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public WebClient client() {
        return webClient;
    }
}*/
