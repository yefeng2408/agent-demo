package com.yef.agent.graph.eum;

public class SourceAdapter {

    public static Source fromRaw(String raw) {
        if (raw == null) return Source.SYSTEM;

        return switch (raw.toUpperCase()) {
            case "USER", "USER_STATEMENT", "MANUAL", "V2" -> Source.USER_STATEMENT;
            case "QUESTION" -> Source.QUESTION;
            case "SELF_CORRECTION" -> Source.SELF_CORRECTION;
            case "SYSTEM" -> Source.SYSTEM;
            case "EVOLUTION" -> Source.EVOLUTION;
            default -> Source.SYSTEM; // 兜底防脏数据
        };
    }
}