ExplanationType 设计说明（System-level）

1. 设计目标（Why）

ExplanationType 用于显式区分“解释的语义类型”，
解决以下核心问题：
•	❌ 解释文本混在一起，用户不知道哪些是结论、哪些是提示
•	❌ 系统内部无法排序、去重、裁剪解释
•	❌ 认知状态迁移无法被解释，只能给“结果”

一句话目标：
👉 让 Agent 不仅能“回答”，还能“解释自己是如何走到这个答案的”。





2. ExplanationType 在系统中的角色

在整体架构中的位置

Claim / Evidence
        ↓
DominantDecision
        ↓
DecisionReason
        ↓
EpistemicExplanationRepository
        ↓
List<ExplanationItem>  ← ExplanationType 在这里生效
        ↓
ExplainableAnswerBuilder
        ↓
AnswerResult（对用户）


ExplanationType 不参与裁决逻辑，但：
	•	决定解释如何组合
	•	决定解释如何排序
	•	决定解释是否可去重
	•	决定解释是否展示给用户






3. ExplanationType 枚举定义

public enum ExplanationType {

    /**
     * 核心裁决原因
     */
    DECISION_REASON,

    /**
     * 冲突说明（存在相互矛盾的认知）
     */
    CONFLICT_NOTICE,

    /**
     * 置信度 / 不确定性提示
     */
    CONFIDENCE_NOTICE,

    /**
     * 引导用户澄清或补充信息
     */
    FOLLOW_UP_SUGGESTION,

    /**
     * 认知状态迁移说明（解释“为何从 A 变成 B”）
     */
    STATUS_TRANSITION
}






4. 各类型的语义与使用规范（非常重要）

4.1 DECISION_REASON（裁决原因）

语义
•	当前回答“成立 / 不成立 / 不确定”的直接原因
•	必须来自系统裁决逻辑（如 DominantDecision）

特性
属性             说明
是否唯一          是（通常 1 条）
是否影响裁决。     ✅ 是
是否可去重        是
优先级           最高

示例
“该结论基于用户明确且一致的陈述。”


4.2 CONFLICT_NOTICE（冲突说明）

语义
•	告知用户：当前存在相互冲突的认知
•	不表达立场，不做裁决

特性
属性             说明
是否影响裁决      ❌ 否
是否可多条        可以
是否引导用户      间接

示例
“存在多条相互冲突但都被确认的陈述。”


4.3 CONFIDENCE_NOTICE（置信度说明）

语义
•	解释为什么当前结论不够稳定 / 尚不充分
•	通常与 fallback / weak support 相关

特性
属性             说明
是否裁决          ❌
是否解释不确定性   ✅
是否中立          是

示例
“这些说法尚不足以形成稳定结论。”


4.4 FOLLOW_UP_SUGGESTION（追问建议）

语义
•	明确告诉用户：
“你再说点什么，系统就能更确定”

特性
属性            说明
是否裁决         ❌
是否引导用户      ✅
是否可多个       可以

示
“你可以说明最近一次状态变化发生在什么时候。”


4.5 STATUS_TRANSITION（认知状态迁移说明）

⚠️ 这是 v3 系统新增的关键类型

语义
•	解释：认知是如何从一个状态演化到当前状态的
•	不等价于裁决原因
•	是“系统记忆演化的可解释性出口”

特性
属性            说明
是否裁决         ❌
是否事实陈述      ❌
是否解释过程      ✅
是否允许多个      通常 1 条（最新）

示例
“该结论基于一次认知状态变化，由 HYPOTHETICAL 转变为 CONFIRMED。”
注意
	•	❌ 不能替代 DECISION_REASON
	•	❌ 不能参与裁决
	•	✅ 只能用于解释“过程”




5. 严格设计约束（必须遵守）

❌ 禁止行为
	•	用 DECISION_REASON 描述状态迁移
	•	用 STATUS_TRANSITION 表达立场
	•	在 explain 层修改 claim / status
	•	让解释反向影响裁决

✅ 允许行为
	•	多类型解释并存
	•	解释按 priority 排序
	•	不同类型解释分别去重
	•	状态迁移解释作为“补充说明”








