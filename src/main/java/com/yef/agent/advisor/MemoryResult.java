package com.yef.agent.advisor;

import java.util.List;

/**
 * 用于承载【从用户输入中抽取的事实】
 * 注意：这里只存“确定无疑”的信息
 */
public class MemoryResult {

    /**
     * 是否抽取到了任何事实
     */
    private boolean hasFacts;

    /**
     * 抽取出的事实列表（每一条都是一句“可以长期记忆的事实”）
     * 示例：
     * - 用户的名字是叶丰。
     * - 用户今年33岁。
     * - 用户的爱好是健身、游泳和跑步。
     */
    private List<String> facts;

    /**
     * 置信度：high / low
     * 只有 high 才允许写入长期记忆
     */
    private String confidence;

    /* ================== 核心方法 ================== */

    /**
     * 是否存在“高置信度事实”
     */
    public boolean hasHighConfidenceFacts() {
        return hasFacts
                && facts != null
                && !facts.isEmpty()
                && "high".equalsIgnoreCase(confidence);
    }

    /**
     * 将事实拼接为向量库可存储文本
     */
    public String toMemoryText() {
        if (facts == null || facts.isEmpty()) {
            return "";
        }
        return String.join("\n", facts);
    }

    /* ================== Getter / Setter ================== */

    public boolean isHasFacts() {
        return hasFacts;
    }

    public void setHasFacts(boolean hasFacts) {
        this.hasFacts = hasFacts;
    }

    public List<String> getFacts() {
        return facts;
    }

    public void setFacts(List<String> facts) {
        this.facts = facts;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }
}