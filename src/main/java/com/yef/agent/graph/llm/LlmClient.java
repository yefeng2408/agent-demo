package com.yef.agent.graph.llm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LlmClient {

    private final ChatClient chatClient;

    public LlmClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String chat(String systemPrompt, String userPrompt){

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

}