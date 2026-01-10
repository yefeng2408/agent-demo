package com.yef.agent.chat;

import com.yef.agent.service.InsuranceService;
import com.yef.agent.service.MedAgentService;
import com.yef.agent.vo.StudentResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/chat")
public class ChatController {
    //基础版的chatClient
    private final ChatClient chatClient;
    //医疗看诊的chatClient
    private final ChatClient medicalChatClient;

    private final InsuranceService insuranceService;
    private final MedAgentService medAgentService;
    //个人聊天的hatClient
    private final ChatClient personalChatClient;


    public ChatController(ChatClient chatClient, ChatClient.Builder builder,
                          InsuranceService insuranceService,
                          MedAgentService medAgentService,
                          @Qualifier("personalChatClient") ChatClient personalChatClient) {
        this.chatClient = chatClient;
        // 在构造时绑定工具函数
        this.medicalChatClient = builder
                .defaultFunctions("insuranceOrderTool")
                .build();
        this.insuranceService = insuranceService;
        this.medAgentService=medAgentService;
        this.personalChatClient=personalChatClient;

    }

    /**
     * 专业问诊接口
     * @param conversationId 建议前端传一个唯一ID，比如 UUID，用来区分不同人的对话
     * @param message 患者的描述
     */
    @GetMapping("/ask")
    public Mono<ConsultationVo> ask(@RequestParam("cid") String conversationId,
                                    @RequestParam("msg") String message) {
        ConsultationVo chat = medAgentService.medicalChat(conversationId, message);
        return Mono.just(chat);
    }


    /**
     * 个人聊天的窗口，用于保存记忆
     * @param msg
     * @return
     */
    @GetMapping("/personal")
    public Mono<String> personal(@RequestParam String msg) {
        // 这里用带有 UserPersonaAdvisor 的 client，AI 才知道你是叶丰
        return Mono.just(personalChatClient.prompt(msg).call().content());
    }



   /* @GetMapping("/student")
    public Mono<StudentResponse> getStudent() {
        StudentResponse studentResponse = insuranceService.getStudentResponse();
        return Mono.just(studentResponse);
    }*/
}
