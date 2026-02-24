package com.yef.agent.memory.narrative;

public final class NarrativeToneRenderer {

    private NarrativeToneRenderer() {}

    public static String render(NarrativeTone tone, String core) {
        return switch (tone) {
            case ASSERTIVE -> core;                 // “就是这样”
            case CONFIDENT -> core;                 // 可加轻微肯定语
            case CAUTIOUS -> "我倾向认为，" + core;  // 谨慎
            case UNCERTAIN -> "我不太确定，但可能是：" + core;
        };
    }
}