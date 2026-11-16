package com.deadlocksim.model.recovery;

import com.deadlocksim.model.Process;
import com.deadlocksim.model.Resource;
import com.deadlocksim.model.Scenario;

public class Preempt implements RecoveryStrategy {
    private final String resourceId;

    public Preempt(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("Resource ID must not be null or blank");
        }
        this.resourceId = resourceId;
    }

    public String getResourceId() {
        return resourceId;
    }

    @Override
    public void apply(Scenario s, Process victim) {
        if (s == null) {
            throw new IllegalArgumentException("Scenario must not be null");
        }
        if (victim == null) {
            throw new IllegalArgumentException("Victim must not be null");
        }
        if (!s.getProcesses().contains(victim)) {
            throw new IllegalArgumentException("Victim process is not in the scenario");
        }

        int heldCount = victim.getHeld().getOrDefault(resourceId, 0);
        if (heldCount < 1) {
            throw new IllegalStateException("Victim does not hold resource: " + resourceId);
        }

        Resource targetResource = s.resource(resourceId);
        if (s.isShared(targetResource)) {
            throw new IllegalStateException("Cannot preempt shared resource: " + resourceId);
        }
        s.releaseAll(victim);
        victim.rollbackState();
    }
}

