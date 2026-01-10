package com.yef.agent.basicmemory;

import java.util.List;
import java.util.stream.Collectors;

// 最终拼接到 Prompt 里的背景
public record UserPersona(List<UserAttribute> attributes) {
    public String toPromptString() {
        return attributes.stream()
                .map(a -> a.key() + ": " + a.value())
                .collect(Collectors.joining(", "));
    }
}