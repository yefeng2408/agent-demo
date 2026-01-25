package com.yef.agent.memory;

public enum DeltaDirection {
    UP,     // 置信度增强（support / reinforce）
    DOWN,    // 置信度削弱（oppose / refute）
    FLAT;

    public static DeltaDirection fromDelta(double delta) {
        if (delta > 0) return UP;
        if (delta < 0) return DOWN;
        return FLAT;
    }
}