/*
package com.yef.agent.graph;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class LlmPolisher {

    private final ChatClient personalChatClient;

    public LlmPolisher(@Qualifier("personalChatClient") ChatClient personalChatClient) {
        this.personalChatClient = personalChatClient;
    }

    public String polish(String question, String graphAnswer, String ctx) {
        String system = """
            你是“表达层”模型，只能根据提供的 Graph Evidence 输出。
            严禁引入未在证据中出现的新事实。
            输出只要最终回答文本，不要解释规则。
            """;

        String user = """
            %s
            
            请输出最终回答：
            """.formatted(ctx);

        return personalChatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .content();
    }
}*/
