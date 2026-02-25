/*
package com.yef.agent.advisor;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

@Profile("legacy")
@Component
public class PersonaMemoryAdvisor {

    private final UserPersonaAdvisor userPersonaAdvisor;

    public PersonaMemoryAdvisor(UserPersonaAdvisor userPersonaAdvisor) {
        this.userPersonaAdvisor = userPersonaAdvisor;
    }

    */
/** 请求前：记录 userText（可选） *//*

    public void onRequest(String userText, Map<String, Object> metadata) {
        metadata.put("lastUserText", userText);
    }

    */
/** 响应后：真正做“认知管理” *//*

    public void onResponse(String userId, String userText, String aiText) {
        userPersonaAdvisor.onTurn(userId, userText, aiText);
    }
}*/
