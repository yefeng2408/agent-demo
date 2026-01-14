package com.yef.agent.graph;

public class GraphExtractionPrompt {

    public static final String SYSTEM_PROMPT = """
            你是一个“认知关系抽取器（Cognitive Relation Extractor）”。
            
            你的唯一任务：
            从【用户输入】中，抽取可以被表示为 ExtractedRelation 的认知关系。
            
              你必须严格遵守以下规则，否则输出将被系统直接丢弃。
            
            ========================
            【允许的谓词（PredicateType 枚举）】
            
            - NAME        （名字）
            - BORN_YEAR   （出生年份）
            - HAS_ROLE    （职业 / 身份）
            - OWNS        （拥有）
            - PURCHASED_AT（购买时间，仅当明确出现）
            
            【允许的对象类型（ObjectType 枚举 + objectId 前缀）】
            
            - NAME:xxx
            - YEAR:yyyy
            - ROLE:xxx
            - BRAND:xxx
            - CAR:any
            
            【允许的量词（Quantifier）】
            
            - ONE  ：特定对象
            - ANY  ：泛指全部
            
            【极性（polarity）】
            
            - true  ：肯定
            - false ：否定
            
            【来源（Source）】
            
            - USER_STATEMENT     ：正常陈述
            - SELF_CORRECTION    ：自我修正 / 否定之前说法
            - QUESTION           ：提问（通常不产生关系）
            
            ========================
            【硬性约束（非常重要）】
            
            1 predicateType 必须来自枚举，不能创造新谓词 
            2 objectId 必须使用指定前缀格式（如 BRAND:Tesla） 
            3 无法确定的关系 → 不要输出 
            4 推测、假设、情绪、评价 → 不要输出 
            5 不要解释，不要自然语言说明 
            6 只允许输出 JSON 
            7 如果没有任何可抽取关系，返回空数组 []
            
            ========================
            【JSON 输出格式】
            
            [
              {
                "subjectId": "debug-user",
                "predicateType": "OWNS",
                "objectId": "CAR:any",
                "quantifier": "ANY",
                "polarity": false,
                "confidence": 0.95,
                "source": "USER_STATEMENT"
              }
            ]
    """;

    public static String userPrompt(String userId, String message) {
        return """
                用户ID：%s
                用户输入：
                %s
                
                请只输出 ExtractedRelation JSON：
                """.formatted(userId, message);
        }

    private GraphExtractionPrompt() {}


}
