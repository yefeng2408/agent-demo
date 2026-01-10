package com.yef.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yef.agent.chat.ConsultationVo;
import com.yef.agent.chat.MedicalRecord;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class MedAgentService {
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Qualifier("chatClient")
    @Autowired
    private ChatClient chatClient;


    // MedAgentService.java 中修改 chat 方法
    public ConsultationVo medicalChat(String conversationId, String userMessage) {
        saveMessage(conversationId, "user", userMessage, null);

        String history = getHistory(conversationId);
        String aiResponse = chatClient.prompt()
                .user(history + "\n患者新消息: " + userMessage)
                .call()
                .content();

        // 关键点：提取病历
        MedicalRecord record = extractMedicalInfo(userMessage + aiResponse);

        saveMessage(conversationId, "assistant", aiResponse, record);

        // 返回组合对象
        return new ConsultationVo(aiResponse, record);
    }



    public MedicalRecord extractMedicalInfo(String chatContext) {
        // 强制 AI 将非结构化文本转为我们的 record
        return chatClient.prompt()
                .user(u -> u.text("""
                你是一名医疗数据处理员。请从以下对话中提取核心病历信息。
                对话内容: {context}
                """)
                        .param("context", chatContext))
                .call()
                .entity(MedicalRecord.class); // Spring AI 会自动注入 format 指令并解析结果
    }


    private String getHistory(String conversationId) {
        String sql = "SELECT role, content FROM ai_medical_chat_history WHERE conversation_id = ? ORDER BY create_time ASC LIMIT 10";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, conversationId);

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            String role = (String) row.get("role");
            String content = (String) row.get("content");
            // 将历史记录格式化为 AI 能读懂的对话流
            sb.append(role.equals("user") ? "患者: " : "医生: ").append(content).append("\n");
        }
        return sb.toString();
    }


    private void saveMessage(String cid, String role, String content, MedicalRecord record) {
        String json = null;
        try {
            if (record != null) {
                json = objectMapper.writeValueAsString(record); // 结构化数据转 JSON
            }
        } catch (JsonProcessingException e) {
            // 上海老兵提醒：实际项目中这里要记 Log
            e.printStackTrace();
        }

        jdbcTemplate.update(
                "INSERT INTO ai_medical_chat_history (conversation_id, role, content, extracted_data) VALUES (?, ?, ?, ?)",
                cid, role, content, json
        );
    }

}