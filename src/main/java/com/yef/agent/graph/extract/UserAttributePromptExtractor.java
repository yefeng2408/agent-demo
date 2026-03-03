package com.yef.agent.graph.extract;

public class UserAttributePromptExtractor {
    /*
     * 第一层：强约束 Structured Output
     * 优先使用：
     * 	•	OpenAI JSON schema mode
     * 	•	或 function calling
     * 	•	或强 schema response_format
     *
     * 第二层：服务端 JSON 校验
     * 	try {
     *     Map<String,Object> parsed = objectMapper.readValue(response, Map.class);
     * } catch (Exception e) {
     *     //如果 JSON 解析失败,进入重试
     * }
     *
     * 第三层：自动纠错重试 Prompt
     * 如果第一次输出失败：
     * 发送第二次 Prompt：
     * The previous output was invalid JSON.
     *
     * Return ONLY valid JSON.
     * Do not include explanations.
     * Do not include markdown.
     *
     * 第四层：字段白名单过滤
     * 即使 JSON 合法，也要过滤字段：
     * Set<String> allowed = Set.of("count","colors","brand","location","price","time");
     * Map<String,Object> attrs = parsed.get("attributes");
     * attrs.keySet().removeIf(k -> !allowed.contains(k));
     *
     * 第五层：异常降级机制
     * 如果两次重试都失败：
     * return emptyAttributes(); 不要阻塞主流程。
     *
     * 让模型直接输出合法 JSON
     * 使用流程：
     * User ASSERT
     *    ↓
     * LLM Structured Extraction
     *    ↓
     * JSON Parse
     *    ↓
     * Field Whitelist Filter
     *    ↓
     * Save to Attribute Store
     *    ↓
     * Continue Graph Pipeline
     *
     *
     * //TODO if extractor fail, need to retry
     *
     * Parse Error
     *    ↓
     * Retry with strict correction prompt
     *    ↓
     * Still fail?
     *    ↓
     * Return empty attributes
     *
     * 关键原则:
     *	1.	Attribute 不影响 confidence
     * 	2.	Attribute 不参与 delta
     * 	3.	Attribute 不参与 beliefState
     * 	4.	Attribute 只是表达层补充
     * 	5.	Attribute 抽取失败不影响 Graph
     */
    String prompt = """
            Extract structured attributes from the following user statement.
            
            Only extract attributes explicitly written.
            
            Allowed attribute fields:
            - count (number)
            - colors (array of strings)
            - brand (string)
            - location (string)
            - price (number)
            - time (string)
            
            Important:
            - If an attribute is not explicitly written, do NOT include it.
            - If no attribute exists, return empty attributes object.
            - Do NOT infer.
            - Do NOT guess.
            - Return JSON only.
            
            User Statement:
            "{USER_MESSAGE}"
            
            Return JSON in this format:
            
            {
              "attributes": {
                  ...
              }
            }
            """;
}
