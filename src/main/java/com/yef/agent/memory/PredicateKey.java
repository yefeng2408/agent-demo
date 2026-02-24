package com.yef.agent.memory;

public record PredicateKey(
        String name,      // user_owns_car
        String argument   // Tesla / any
) {
    public boolean isAny() {
        return "any".equalsIgnoreCase(argument);
    }

    public String toProposition() {
        return name + "(" + argument + ")";
    }

    public PredicateKey toAny() {
        return new PredicateKey(name, "any");
    }

    public boolean samePredicate(PredicateKey other) {
        return this.name.equals(other.name);
    }

    public boolean isSpecificOf(PredicateKey other) {
        return samePredicate(other) && other.isAny() && !this.isAny();
    }


    public static PredicateKey parse(String proposition) {
        // user_owns_car(Tesla)
        int l = proposition.indexOf('(');
        int r = proposition.lastIndexOf(')');
        if (l < 0 || r < 0) {
            throw new IllegalArgumentException("Invalid proposition: " + proposition);
        }
        String name = proposition.substring(0, l);
        String arg = proposition.substring(l + 1, r);
        return new PredicateKey(name, arg);
    }

}