package com.yef.agent.graph.llm;

import com.yef.agent.graph.answer.AnswerResult;
import com.yef.agent.graph.answer.Citation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

@Component
public class ExplainableAnswerPolisher implements LlmPolisher {

    private final static String EXPLAINER_SYSTEM_PROMPT=
            """
            你是一个“解释器（explainer）”，不是推理器。
            
            你必须遵守：
            1. 不得新增任何事实
            2. 不得修改结论
            3. 不得引入不在证据中的信息
            4. 只能基于给定的裁决（Decision）和证据（Citations）进行解释
            5. 如果证据不足，只能说明“不确定”，不能自行推断
            """;

    private final ChatClient chatClient;

    public ExplainableAnswerPolisher(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String explain(AnswerResult result) {
        if (result == null || !result.answered()) {
            return result.answer();
        }
        String decision = result.relation().toReadableText();
        String citations = result.citations().stream()
                .map(Citation::toExplainText)
                .collect(Collectors.joining("\n"));

        String prompt = """
        【最终裁决】
        %s

        【证据】
        %s

        请用简洁、自然的语言解释“为什么得到这个结论”。
        不要新增任何事实。
        """.formatted(decision, citations);

        return chatClient.prompt()
                .system(EXPLAINER_SYSTEM_PROMPT)
                .user(prompt)
                .call()
                .content();
    }
}