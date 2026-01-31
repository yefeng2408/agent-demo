🧠 Self-Healing（自我修复子系统）

🧠 Self-Healing（自我修复子系统）

Self-Healing 是 Agent 认知系统中的“后台演化引擎”
它不负责当下裁决，而负责让认知系统 在冲突中保持可解释、可演化、可回放。

⸻

一、设计目标（Why Self-Healing）

在真实对话中，Agent 会持续遇到以下情况：
	•	用户 前后自相矛盾
	•	同一 proposition 同时存在 正 / 反 claim
	•	新信息不足以立即推翻旧认知
	•	但如果什么都不做，系统将 永远停留在混乱态

Self-Healing 的核心目标不是「消灭冲突」，而是：

在不裁决的前提下，让冲突“变得有结构”

具体目标包括：
	•	显式记录冲突的发生
	•	建立 claim 之间的语义关系（支持 / 反对 / 修正）
	•	对置信度进行渐进式演化
	•	为未来的裁决提供 结构化线索（Hint）
	•	保证所有变化 可解释、可回放、可审计

⸻

二、系统定位（What It Is / Is Not）

✅ Self-Healing 是什么
	•	一个 异步运行 的认知演化模块
	•	处理 EpistemicEvent（认知事件）
	•	输出 ClaimMutation（认知变更原语）
	•	不改变 Answer 的即时结果
	•	不破坏 claim 的并存性

❌ Self-Healing 不是什么
	•	❌ 不是裁决器（不决定真 / 假）
	•	❌ 不是状态机（不直接迁移 epistemicStatus）
	•	❌ 不是规则引擎（不强制推理）

⸻

三、整体流程（High-Level Flow）

    User Input
       ↓
    EpistemicEvent
       ↓
    [Async] HandleEpistemicEventAsyncTask
       ↓
    SelfCorrectionContextBuilder
       ↓
    SelfCorrectionResolver
       ↓
    SelfCorrectionResult
       ↓
    ClaimMutationApplier
       ↓
    Graph / State updated (non-destructive)
⚠️ 注意：
Self-Healing 的执行结果不会立即影响 Answer 的三态裁决





四、核心概念说明

1️⃣ EpistemicEvent

表示一次“认知冲击”，例如：
	•	用户提出新主张
	•	用户否定旧主张
	•	用户明确纠错
	•	来自对话 / 问题 / 更正的输入

它是 Self-Healing 的唯一入口。

⸻

2️⃣ SelfCorrectionContext

Self-Healing 的“观察窗口”，包含：
	•	新进入系统的 claim（newClaim）
	•	与之冲突的已有 claim（conflictedClaims）
	•	用户、时间、事件类型、原因

可以理解为：
“在某个时间点，认知世界长什么样？”

⸻

3️⃣ SelfCorrectionResolver

Self-Healing 的决策核心。

职责：
	•	判断：是否值得触发自我修复
	•	生成：一组 ClaimMutation
	•	给出：DominantClaimHint（非裁决）

边界约束：
	•	❌ 不直接修改 claim
	•	❌ 不删除任何认知
	•	❌ 不下最终结论

⸻

4️⃣ SelfCorrectionResult

Resolver 的输出结果：