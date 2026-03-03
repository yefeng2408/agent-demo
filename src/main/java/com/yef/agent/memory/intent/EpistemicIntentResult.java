package com.yef.agent.memory.intent;

/**
 *
 *
 * @param intent
 * @param assertionStrength
 */
public record EpistemicIntentResult(
        /*
         * confidence 代表对该分类的把握程度。
         *
         * SELF_CORRECTION 通常 ≥ 0.85
         * ASSERT_STRONG 通常 0.6~0.85
         * WEAK_ASSERT 通常 0.4~0.6
         * HEDGE 0.2~0.4
         * DOUBT 0.1~0.3
         * NORMAL 0.3~0.5
         */

        EpistemicIntent intent,
        //模型对这次分类结果的自信程度
       /* double classificationConfidence,*/

        /*
         * 它能控制：
         * 	•	delta 权重
         * 	•	支持强度
         * 	•	对 dominant 的冲击
         */
        double assertionStrength
) {}