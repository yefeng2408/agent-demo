package com.yef.agent.chat;

import com.yef.agent.advisor.PersonaMemoryAdvisor;
import com.yef.agent.advisor.UserPersonaAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatClient personalChatClient;
    private final PersonaMemoryAdvisor personaMemoryAdvisor;
    private final UserPersonaAdvisor userPersonaAdvisor;

    public ChatController(@Qualifier("personalChatClient") ChatClient personalChatClient,
                          PersonaMemoryAdvisor personaMemoryAdvisor,
                          UserPersonaAdvisor userPersonaAdvisor) {
        this.personalChatClient = personalChatClient;
        this.personaMemoryAdvisor = personaMemoryAdvisor;
        this.userPersonaAdvisor = userPersonaAdvisor;
    }

    @GetMapping("/personal")
    public String chat(
            @RequestParam String msg,
            @RequestParam(defaultValue = "debug-user") String userId) {
        Map<String, Object> metadata = new HashMap<>();

        // 1. 请求前处理
        personaMemoryAdvisor.onRequest(msg, metadata);

        // 2. 获取用户记忆
        List<String> memories = userPersonaAdvisor.getUserMemories(userId);

        String systemPrompt = """
                                你是一个有长期记忆的 AI 助手。
                                以下是你已知的关于用户的信息：
                                %s
                                """
                .formatted(String.join("\n", memories));

        // 3. 调用 LLM
        String answer = personalChatClient
                .prompt()
                .system(systemPrompt)
                .user(msg)
                .call()
                .content();

        // 4. 响应后处理：记录记忆
        personaMemoryAdvisor.onResponse(userId, msg, answer);

        return answer;
    }
}
