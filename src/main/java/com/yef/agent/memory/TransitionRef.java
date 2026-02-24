package com.yef.agent.memory;


public record TransitionRef(
        String from,
        String to
) {

    public static TransitionRef getTransitionRef(String from,String to) {
        return new TransitionRef(from,to);
    }
}