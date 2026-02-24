package com.yef.agent.memory.momentum;

import com.yef.agent.memory.DeltaDirection;
import org.springframework.stereotype.Component;

@Component
public class DominantMomentumAccumulator {

    public double update(double oldMomentum,
                         DeltaDirection direction) {

        double delta = direction == DeltaDirection.UP
                ? +0.08
                : -0.12;

        double next = oldMomentum + delta;

        return Math.max(0.0, Math.min(1.0, next));
    }
}