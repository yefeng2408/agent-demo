package conflictCalculation;

import com.yef.agent.memory.SlotBeliefState;

import static com.yef.agent.memory.SlotBeliefState.*;

public final class SlotBeliefStateDecider {

    private final double epsilon;           // 例如 0.05
    private final double stableThreshold;   // 例如 0.75

    public SlotBeliefStateDecider(double epsilon, double stableThreshold) {
        this.epsilon = epsilon;
        this.stableThreshold = stableThreshold;
    }


    /**
     * v2 三维结构推导：
     * 由 dominant 结构 + confidence 数值 + challenger 结构共同决定状态
     *
     * @param hasDominant          是否存在 dominant
     * @param dominantConfidence   dominant 的 confidence
     * @param hasChallenger        是否存在 polarity 相反的 challenger
     * @param challengerConfidence challenger 的 confidence（若无可传 0）
     */
    public SlotBeliefState derive(
            boolean hasDominant,
            double dominantConfidence,
            boolean hasChallenger,
            double challengerConfidence
    ) {

        // 2️⃣ 只有一个 claim
        if (hasDominant && !hasChallenger) {
            return evaluateSingle(dominantConfidence);
        }

        // 3️⃣ 存在 challenger
        double diff = Math.abs(dominantConfidence - challengerConfidence);

        // 3.1 差值太小 → 冲突
        if (diff < epsilon) {
            return SlotBeliefState.CONTESTED;
        }

        // 3.2 challenger 更强 → 推翻
        if (challengerConfidence > dominantConfidence) {
            return OVERTURNED;
        }

        // 3.3 dominant 仍然领先
        return evaluateSingle(dominantConfidence);
    }

    private SlotBeliefState evaluateSingle(double confidence) {
        if (confidence >= stableThreshold) {
            return STABLE_CONFIRMED;
        }
        return WEAKLY_CONFIRMED;
    }



    public static SlotBeliefState fromGraph(String raw) {
        if (raw == null) return UNKNOWN;

        return switch (raw) {
            // v3 正规值
            case "STABLE_CONFIRMED" -> STABLE_CONFIRMED;
            case "WEAKLY_CONFIRMED" -> WEAKLY_CONFIRMED;
            case "CONTESTED" -> CONTESTED;
            case "OVERTURNED" -> OVERTURNED;
            // case "UNFORMED" -> UNFORMED;

            default -> UNKNOWN; // 防脏数据
        };
    }

}