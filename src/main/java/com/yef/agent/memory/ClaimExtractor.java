package com.yef.agent.memory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
@Deprecated
public class ClaimExtractor {

    private final ChatClient chatClient;

    public ClaimExtractor(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ClaimExtractionResult extract(String userText, String aiText) {
        String system = """
        你是【认知状态抽取器】。你只做一件事：从对话中抽取“用户自述相关”的命题，并判断其语气与认知状态。
        
        重要规则：
        - 必须区分 assert(陈述) / deny(否认) / hypothetical(假设/如果) / question(询问)
        - 假设语句绝不能当事实
        - proposition 必须规范化，示例：
          user_name(叶丰)
          user_plan(career, primary, 日本求职)
          user_owns_car(Tesla)
        - status 只能取：CONFIRMED / DENIED / HYPOTHETICAL / UNKNOWN
        - confidence 0~1：assert 且明确通常 >=0.85；hypothetical 通常 <=0.4；question 通常 <=0.3
        
        只允许输出 JSON，不要解释：
        {
          "hasClaims": true,
          "claims": [
            {
              "proposition":"user_owns_car(Tesla)",
              "surface":"用户明确表示自己有一辆特斯拉",
              "modality":"assert",
              "status":"CONFIRMED",
              "confidence":0.92
            }
          ]
        }
        """;

        String user = "用户说: " + userText + "\nAI答: " + (aiText == null ? "" : aiText);

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(ClaimExtractionResult.class);
    }


}