package com.deadlocksim.engine;

import com.deadlocksim.model.SafetyResult;

public record RequestResult(RequestOutcome outcome, String reason, SafetyResult safety) {
    public RequestResult(RequestOutcome outcome, String reason) {
        this(outcome, reason, null);
    }
}

