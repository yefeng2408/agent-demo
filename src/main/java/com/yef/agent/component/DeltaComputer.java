package com.yef.agent.component;

import org.springframework.stereotype.Component;

@Component
public class DeltaComputer {

    public double computeStep(double baseConfidence) {
       /* double step = 0.05;
        return Math.min(step, 1.0 - baseConfidence);*/
        double baseStep = 0.1;
        double semanticStrength = baseConfidence;
        double instability = 1.0 - baseConfidence;
        return baseStep * semanticStrength * instability;
    }

    /*public double computeDelta(double baseConfidence, boolean increase) {
        double step = 0.05;
        if (increase) {
            return Math.min(step, 1.0 - baseConfidence);
        } else {
            return Math.min(step, baseConfidence);
        }
    }*/

}
