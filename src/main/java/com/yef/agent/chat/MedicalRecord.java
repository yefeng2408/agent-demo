package com.yef.agent.chat;

import java.util.List;

// 对应你之前提到的“提取核心信息”
public record MedicalRecord(
        String mainSymptom,    // 主诉（哪里疼）
        String duration,       // 持续时间
        List<String> riskTags, // 风险标签（如：高热、过敏）
        String department      // 建议科室
) {
    // 可以在这里加一个静态方法，方便返回“空记录”
    public static MedicalRecord empty() {
        return new MedicalRecord("未知", "未知", List.of(), "全科");
    }
}
