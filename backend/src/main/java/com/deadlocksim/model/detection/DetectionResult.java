package com.deadlocksim.model.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DetectionResult {
    private final boolean deadlocked;
    private final List<String> processIds;
    private final List<String> cycle;
    private final Map<String, String> recoveryResources;

    private static final DetectionResult NONE = new DetectionResult(
            false,
            Collections.emptyList(),
            Collections.emptyList(),
            Map.of()
    );

    private DetectionResult(boolean deadlocked, List<String> processIds, List<String> cycle,
                            Map<String, String> recoveryResources) {
        this.deadlocked = deadlocked;
        this.processIds = processIds;
        this.cycle = cycle;
        this.recoveryResources = Map.copyOf(recoveryResources);
    }

    public static DetectionResult none() {
        return NONE;
    }

    public static DetectionResult of(List<String> processIds, List<String> cycle) {
        return of(processIds, cycle, Map.of());
    }

    static DetectionResult of(List<String> processIds, List<String> cycle, Map<String, String> recoveryResources) {
        List<String> sortedProcesses = new ArrayList<>(processIds != null ? processIds : Collections.emptyList());
        Collections.sort(sortedProcesses);
        List<String> cycleList = new ArrayList<>(cycle != null ? cycle : Collections.emptyList());

        return new DetectionResult(
                true,
                Collections.unmodifiableList(sortedProcesses),
                Collections.unmodifiableList(cycleList),
                recoveryResources
        );
    }

    public boolean isDeadlocked() {
        return deadlocked;
    }

    public List<String> getProcessIds() {
        return processIds;
    }

    public List<String> getCycle() {
        return cycle;
    }

    public String recoveryResource(String processId) {
        return recoveryResources.get(processId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DetectionResult that = (DetectionResult) o;
        return deadlocked == that.deadlocked &&
                Objects.equals(processIds, that.processIds) &&
                Objects.equals(cycle, that.cycle) &&
                Objects.equals(recoveryResources, that.recoveryResources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deadlocked, processIds, cycle, recoveryResources);
    }

    @Override
    public String toString() {
        return "DetectionResult{" +
                "deadlocked=" + deadlocked +
                ", processIds=" + processIds +
                ", cycle=" + cycle +
                '}';
    }
}
