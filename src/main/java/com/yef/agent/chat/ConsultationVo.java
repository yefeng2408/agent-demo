package com.yef.agent.chat;

public record ConsultationVo(
        String aiReply,           // AI 回复给用户的话
        MedicalRecord diagnostics // 咱们手搓提取出的结构化干货
) {}