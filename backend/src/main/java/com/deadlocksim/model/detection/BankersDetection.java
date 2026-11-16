package com.deadlocksim.model.detection;

import com.deadlocksim.model.BankersCore;
import com.deadlocksim.model.Graph;
import com.deadlocksim.model.Process;
import com.deadlocksim.model.Resource;
import com.deadlocksim.model.Scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;

public class BankersDetection implements DetectionStrategy {

    @Override
    public DetectionResult detect(Graph g) {
        if (g == null) {
            throw new IllegalArgumentException("Graph must not be null");
        }
        Scenario scenario = g.getScenario();
        Map<String, Long> work = new LinkedHashMap<>();
        for (Resource resource : scenario.getResources()) {
            if (!scenario.isShared(resource)) {
                work.put(resource.getId(), (long) resource.getAvailable());
            }
        }

        Map<String, Map<String, Integer>> allocations = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> needs = new LinkedHashMap<>();
        for (Process process : scenario.getProcesses()) {
            if (process.isTerminal()) continue;
            Map<String, Integer> held = new LinkedHashMap<>(process.getHeld());
            held.keySet().retainAll(work.keySet());
            if (held.isEmpty()) continue;

            Map<String, Integer> demand = process.isWaiting()
                    ? new LinkedHashMap<>(process.getPendingRequest().demand()) : new LinkedHashMap<>();
            demand.keySet().retainAll(work.keySet());
            allocations.put(process.getId(), held);
            needs.put(process.getId(), demand);
        }

        BankersCore.FinishResult result = BankersCore.run(work, allocations, needs);
        List<String> stuck = result.stuck();
        for (Process process : scenario.getProcesses()) {
            process.setDeadlocked(stuck.contains(process.getId()));
        }
        if (stuck.isEmpty()) return DetectionResult.none();
        return DetectionResult.of(stuck, List.of(), recoveryResources(scenario, result, allocations, needs));
    }

    private static Map<String, String> recoveryResources(Scenario scenario, BankersCore.FinishResult result,
                                                         Map<String, Map<String, Integer>> allocations,
                                                         Map<String, Map<String, Integer>> needs) {
        List<String> stuck = result.stuck();
        int size = stuck.size();
        String[][] blocker = new String[size][size];
        boolean[][] reaches = new boolean[size][size];

        for (int waiter = 0; waiter < size; waiter++) {
            for (var need : needs.get(stuck.get(waiter)).entrySet()) {
                String resource = need.getKey();
                if (need.getValue() <= result.remaining().get(resource)) continue;
                int capacity = scenario.resource(resource).getTotal();
                for (int holder = 0; holder < size; holder++) {
                    int held = allocations.get(stuck.get(holder)).getOrDefault(resource, 0);
                    if (held == 0 || (waiter == holder && need.getValue() <= capacity - held)) continue;
                    String previous = blocker[waiter][holder];
                    if (previous == null || resource.compareTo(previous) < 0) {
                        blocker[waiter][holder] = resource;
                    }
                    reaches[waiter][holder] = true;
                }
            }
        }

        // A holder can break the deadlock only when its blocking edge belongs to a cycle.
        for (int via = 0; via < size; via++) {
            for (int from = 0; from < size; from++) {
                for (int to = 0; to < size; to++) {
                    reaches[from][to] |= reaches[from][via] && reaches[via][to];
                }
            }
        }

        Map<String, String> eligible = new LinkedHashMap<>();
        for (int waiter = 0; waiter < size; waiter++) {
            for (int holder = 0; holder < size; holder++) {
                String resource = blocker[waiter][holder];
                if (resource != null && reaches[holder][waiter]) {
                    eligible.merge(stuck.get(holder), resource, BinaryOperator.minBy(String::compareTo));
                }
            }
        }
        return eligible;
    }
}
