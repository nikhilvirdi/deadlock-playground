package com.deadlocksim.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Snapshot {
    private final Map<String, ProcessState> processStates;
    private final Map<String, Map<String, Integer>> processHeld;
    private final Map<String, Boolean> processDeadlocked;
    private final Map<String, Integer> available;
    private final Map<String, Process.PendingRequest> pendingRequests;
    private final Map<String, Integer> instructionsExecuted;
    private final Map<String, Integer> processNextIndex;

    private Snapshot(Map<String, ProcessState> processStates,
                     Map<String, Map<String, Integer>> processHeld,
                     Map<String, Boolean> processDeadlocked,
                     Map<String, Integer> available,
                     Map<String, Process.PendingRequest> pendingRequests,
                     Map<String, Integer> instructionsExecuted,
                     Map<String, Integer> processNextIndex) {
        this.processStates = processStates;
        this.processHeld = processHeld;
        this.processDeadlocked = processDeadlocked;
        this.available = available;
        this.pendingRequests = pendingRequests;
        this.instructionsExecuted = instructionsExecuted;
        this.processNextIndex = processNextIndex;
    }

    public static Snapshot capture(Scenario s) {
        if (s == null) {
            throw new IllegalArgumentException("Scenario must not be null");
        }
        Map<String, ProcessState> states = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> held = new LinkedHashMap<>();
        Map<String, Boolean> deadlocked = new LinkedHashMap<>();
        Map<String, Integer> avail = new LinkedHashMap<>();
        Map<String, Process.PendingRequest> pendingRequests = new LinkedHashMap<>();
        Map<String, Integer> instructionsExecuted = new LinkedHashMap<>();
        Map<String, Integer> nextIndex = new LinkedHashMap<>();

        for (Process p : s.getProcesses()) {
            states.put(p.getId(), p.getState());
            held.put(p.getId(), Collections.unmodifiableMap(new LinkedHashMap<>(p.getHeld())));
            deadlocked.put(p.getId(), p.isDeadlocked());
            if (p.isWaiting()) {
                pendingRequests.put(p.getId(), p.getPendingRequest());
            }
            instructionsExecuted.put(p.getId(), p.getInstructionsExecuted());
            nextIndex.put(p.getId(), p.getNextOperationIndex());
        }

        for (Resource r : s.getResources()) {
            avail.put(r.getId(), r.getAvailable());
        }

        return new Snapshot(
                Collections.unmodifiableMap(states),
                Collections.unmodifiableMap(held),
                Collections.unmodifiableMap(deadlocked),
                Collections.unmodifiableMap(avail),
                Collections.unmodifiableMap(pendingRequests),
                Collections.unmodifiableMap(instructionsExecuted),
                Collections.unmodifiableMap(nextIndex)
        );
    }

    public void restoreInto(Scenario s) {
        if (s == null) {
            throw new IllegalArgumentException("Scenario must not be null");
        }
        Set<String> scenarioProcessIds = s.getProcesses().stream()
                .map(Process::getId)
                .collect(Collectors.toSet());
        if (!scenarioProcessIds.equals(processStates.keySet())) {
            throw new IllegalArgumentException("Scenario process IDs do not match snapshot process IDs");
        }

        Set<String> scenarioResourceIds = s.getResources().stream()
                .map(Resource::getId)
                .collect(Collectors.toSet());
        if (!scenarioResourceIds.equals(available.keySet())) {
            throw new IllegalArgumentException("Scenario resource IDs do not match snapshot resource IDs");
        }

        for (Process p : s.getProcesses()) {
            String pid = p.getId();
            p.setState(processStates.get(pid));
            p.setDeadlocked(processDeadlocked.get(pid));
            p.restoreHeld(processHeld.get(pid));
            p.setPendingRequest(pendingRequests.get(pid));
            p.restoreNextOperationIndex(processNextIndex.get(pid));
            p.restoreInstructionsExecuted(instructionsExecuted.get(pid));
        }

        for (Resource r : s.getResources()) {
            String rid = r.getId();
            r.restoreAvailable(available.get(rid));
        }
    }

    public Map<String, ProcessState> getProcessStates() {
        return processStates;
    }

    public Map<String, Map<String, Integer>> getProcessHeld() {
        return processHeld;
    }

    public Map<String, Boolean> getProcessDeadlocked() {
        return processDeadlocked;
    }

    public Map<String, Integer> getAvailable() {
        return available;
    }

    public Map<String, String> getProcessWaitingResource() {
        Map<String, String> resources = new LinkedHashMap<>();
        pendingRequests.forEach((id, request) -> resources.put(id, request.resourceId()));
        return Collections.unmodifiableMap(resources);
    }

    public Map<String, Integer> getProcessWaitingCount() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        pendingRequests.forEach((id, request) -> counts.put(id, request.count()));
        return Collections.unmodifiableMap(counts);
    }

    public Map<String, Integer> getProcessNextIndex() {
        return processNextIndex;
    }
}
