package com.deadlocksim.engine;

public final class SimulationException extends IllegalStateException {
    public enum Kind {
        NO_SCENARIO, NO_DEADLOCK, NOT_HELD, PREVENTION_VIOLATION, PREEMPTION_VIOLATION,
        VALIDATION_ERROR
    }

    private final Kind kind;

    public SimulationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
