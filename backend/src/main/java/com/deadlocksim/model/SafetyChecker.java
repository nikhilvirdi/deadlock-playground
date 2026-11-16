package com.deadlocksim.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SafetyChecker {
    private SafetyChecker() {}

    public static SafetyResult check(Scenario scenario, String processId, String resourceId, int count) {
        if (scenario == null || processId == null || resourceId == null || count <= 0) {
            throw new IllegalArgumentException("Scenario, process, resource and positive count are required");
        }
        Process process = scenario.getProcesses().stream().filter(p -> p.getId().equals(processId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown process: " + processId));
        scenario.resource(resourceId);
        return check(scenario, process, Map.of(resourceId, count));
    }

    public static SafetyResult check(Scenario scenario, Process requester, Map<String, Integer> request) {
        if (!scenario.getProcesses().contains(requester) || requester.isTerminal()) {
            throw new IllegalArgumentException("An active requesting process must belong to the scenario");
        }
        request.forEach((id, count) -> {
            scenario.resource(id);
            if (count <= 0) throw new IllegalArgumentException("Count must be greater than zero");
        });
        Map<String, Long> work = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> allocations = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> needs = new LinkedHashMap<>();

        for (Resource resource : scenario.getResources()) {
            if (scenario.isShared(resource)) continue;
            String id = resource.getId();
            long remaining = (long) resource.getAvailable() - request.getOrDefault(id, 0);
            if (remaining < 0) {
                return SafetyResult.unsafe("Insufficient available instances of resource " + id, List.of());
            }
            work.put(id, remaining);
        }

        for (Process process : scenario.getProcesses()) {
            if (process.isTerminal()) continue;
            Map<String, Integer> claim = scenario.maximumClaims(process.getId());
            Map<String, Integer> held = process.getHeld();
            Map<String, Integer> allocation = new LinkedHashMap<>();
            Map<String, Integer> need = new LinkedHashMap<>();
            for (Resource resource : scenario.getResources()) {
                String id = resource.getId();
                long count = (long) held.getOrDefault(id, 0) + (process == requester ? request.getOrDefault(id, 0) : 0);
                int maximum = claim.getOrDefault(id, 0);
                if (count > maximum) {
                    return SafetyResult.unsafe("Allocation exceeds the declared claim of " + process.getId()
                            + " for " + id, List.of());
                }
                if (!scenario.isShared(resource)) {
                    allocation.put(id, (int) count);
                    need.put(id, maximum - (int) count);
                }
            }
            allocations.put(process.getId(), allocation);
            needs.put(process.getId(), need);
        }

        BankersCore.FinishResult result = BankersCore.run(work, allocations, needs);
        if (result.stuck().isEmpty()) {
            return SafetyResult.safe("Safe sequence: " + result.sequence(), result.sequence());
        }
        return SafetyResult.unsafe("Unsafe state: processes " + result.stuck() + " cannot finish", result.sequence());
    }
}
