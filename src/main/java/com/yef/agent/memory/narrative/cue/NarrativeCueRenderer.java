/*
package com.yef.agent.memory.narrative.cue;

import com.yef.agent.memory.narrative.NarrativeCondition;
import com.yef.agent.memory.narrative.NarrativeRenderContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.yef.agent.memory.narrative.NarrativeCondition.CONTESTED;

public final class NarrativeCueRenderer {

    private NarrativeCueRenderer() {}

    public static String renderPrefix(NarrativeRenderContext ctx) {
        List<NarrativeCue> cues = ctx.cues();
        Set<NarrativeCue> set = cues == null ? EnumSet.noneOf(NarrativeCue.class) : EnumSet.copyOf(cues);
        StringBuilder sb = new StringBuilder();
        // cues -> prefix（统一集中在这里，Builder 不碰）
        if (set.contains(NarrativeCue.RECENTLY_CHANGED_CUE)) sb.append("根据最新记录，");
        if (set.contains(NarrativeCue.OWNERSHIP_CUE)) sb.append("目前信息显示，");
        if (set.contains(NarrativeCue.CONTESTED_CUE)) sb.append("这一点仍存在争议，");

        // condition -> prefix（更“纲领性”的提示）
        sb.append(switch (condition) {
            case CERTAIN -> "";
            case TENTATIVE -> "可能是这样的：";
            case CONTESTED -> "不同记录之间存在冲突：";
            case RECENTLY_CHANGED -> "刚发生变化：";
        });
        return sb.toString();
    }

    public static String renderSuffix(NarrativeCondition condition, List<NarrativeCue> cues) {
        // condition -> suffix（统一）
        return switch (condition) {
            case CERTAIN -> "";
            case TENTATIVE -> "（当前置信度仍在变化中）";
            case CONTESTED -> "（该结论近期被新的信息挑战）";
            case RECENTLY_CHANGED -> "（该信息刚刚发生更新）";
        };
    }
}*/
