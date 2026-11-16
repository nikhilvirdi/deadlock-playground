package com.deadlocksim.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Process {
    private final String id;
    private final int priority;
    private ProcessState state;
    private boolean deadlocked;
    private final Map<String, Integer> held;
    private int instructionsExecuted;
    private PendingRequest pendingRequest;

    public record PendingRequest(String resourceId, int count, Map<String, Integer> demand,
                                 int operationIndex, int operationCount) {
        public PendingRequest {
            demand = Collections.unmodifiableMap(new LinkedHashMap<>(demand));
        }
    }
    private int nextOperationIndex;

    public Process(String id, int priority) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Process ID must not be null or blank");
        }
        this.id = id;
        this.priority = priority;
        this.state = ProcessState.RUNNING;
        this.deadlocked = false;
        this.held = new LinkedHashMap<>();
        this.instructionsExecuted = 0;
        this.nextOperationIndex = 0;
    }

    public String getId() {
        return id;
    }

    public int getPriority() {
        return priority;
    }

    public ProcessState getState() {
        return state;
    }

    public boolean isTerminal() {
        return state == ProcessState.COMPLETED || state == ProcessState.TERMINATED;
    }

    public boolean isDeadlocked() {
        return deadlocked;
    }

    public Map<String, Integer> getHeld() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(held));
    }

    public int getInstructionsExecuted() {
        return instructionsExecuted;
    }

    public int getNextOperationIndex() {
        return nextOperationIndex;
    }

    public void advanceOperation() {
        this.nextOperationIndex = Math.incrementExact(this.nextOperationIndex);
    }

    void restoreNextOperationIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Next operation index must not be negative");
        }
        this.nextOperationIndex = index;
    }

    public void setState(ProcessState s) {
        if (s == null) {
            throw new IllegalArgumentException("ProcessState must not be null");
        }
        this.state = s;
    }

    public void setDeadlocked(boolean d) {
        this.deadlocked = d;
    }

    public boolean isBlocked() {
        return this.state == ProcessState.WAITING;
    }

    public void setWaitingFor(String resourceId, int count) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("Resource ID must not be null or blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be greater than zero");
        }
        setPendingRequest(new PendingRequest(resourceId, count, Map.of(resourceId, count), -1, 0));
    }

    public PendingRequest getPendingRequest() {
        return pendingRequest;
    }

    public void setPendingRequest(PendingRequest request) {
        this.pendingRequest = request;
    }

    public void clearWaitingFor() {
        this.pendingRequest = null;
    }

    public String getWaitingResourceId() {
        return pendingRequest == null ? null : pendingRequest.resourceId();
    }

    public int getWaitingCount() {
        return pendingRequest == null ? 0 : pendingRequest.count();
    }

    public boolean isWaiting() {
        return pendingRequest != null;
    }

    public void holdInstances(String resourceId, int n) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("Resource ID must not be null or blank");
        }
        checkHoldingCapacity(n);
        held.put(resourceId, held.getOrDefault(resourceId, 0) + n);
    }

    public void checkHoldingCapacity(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Instance count must be greater than zero");
        }
        if (count > Integer.MAX_VALUE - totalHeld()) {
            throw new IllegalArgumentException("Total instances held by a process cannot exceed " + Integer.MAX_VALUE);
        }
    }

    public void releaseInstances(String resourceId, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("Instance count must be greater than zero");
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("Resource ID must not be null or blank");
        }
        int current = held.getOrDefault(resourceId, 0);
        if (current < n) {
            throw new IllegalStateException("Process holds fewer instances than requested to release");
        }
        if (current == n) {
            held.remove(resourceId);
        } else {
            held.put(resourceId, current - n);
        }
    }

    public int totalHeld() {
        int total = 0;
        for (int count : held.values()) {
            total += count;
        }
        return total;
    }

    public void recordInstruction() {
        this.instructionsExecuted = Math.incrementExact(this.instructionsExecuted);
    }

    void restoreInstructionsExecuted(int count) {
        this.instructionsExecuted = count;
    }

    public void rollbackState() {
        this.state = ProcessState.RUNNING;
        this.deadlocked = false;
        this.held.clear();
        clearWaitingFor();
        this.instructionsExecuted = 0;
        this.nextOperationIndex = 0;
    }

    void restoreHeld(Map<String, Integer> newHeld) {
        if (newHeld == null) {
            throw new IllegalArgumentException("Held map must not be null");
        }
        long total = 0;
        for (Map.Entry<String, Integer> entry : newHeld.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Resource ID must not be null or blank");
            }
            if (entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException("Instance count must be greater than zero");
            }
            total += entry.getValue();
            if (total > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Total instances held by a process cannot exceed " + Integer.MAX_VALUE);
            }
        }
        this.held.clear();
        this.held.putAll(newHeld);
    }
}
