package com.yef.agent.advisor;

import lombok.*;
import java.util.List;

@AllArgsConstructor
@ToString
public class PlanMemoryItem {

    private boolean hasPlan;
    private String confidence;
    private List<Plan> plans;

    public static class Plan {
        private String planType;   // career / travel / learning
        private String planRole;   // primary / fallback
        private String content;

        public String getPlanType() {
            return planType;
        }

        public void setPlanType(String planType) {
            this.planType = planType;
        }

        public String getPlanRole() {
            return planRole;
        }

        public void setPlanRole(String planRole) {
            this.planRole = planRole;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    public boolean isHasPlan() {
        return hasPlan;
    }

    public void setHasPlan(boolean hasPlan) {
        this.hasPlan = hasPlan;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public List<Plan> getPlans() {
        return plans;
    }

    public void setPlans(List<Plan> plans) {
        this.plans = plans;
    }
}
