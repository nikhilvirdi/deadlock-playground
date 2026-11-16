package com.deadlocksim.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BankersCore {
    public record FinishResult(List<String> sequence, List<String> stuck, Map<String, Long> remaining) {
        public FinishResult {
            sequence = List.copyOf(sequence);
            stuck = List.copyOf(stuck);
            remaining = Map.copyOf(remaining);
        }
    }

    private BankersCore() {
    }

    public static FinishResult run(Map<String, Long> available,
                                   Map<String, Map<String, Integer>> allocations,
                                   Map<String, Map<String, Integer>> needs) {
        Map<String, Long> work = new LinkedHashMap<>(available);
        List<String> sequence = new ArrayList<>();
        Set<String> finished = new HashSet<>();

        boolean progress;
        do {
            progress = false;
            for (var entry : needs.entrySet()) {
                String id = entry.getKey();
                if (finished.contains(id) || entry.getValue().entrySet().stream()
                        .anyMatch(need -> need.getValue() > work.getOrDefault(need.getKey(), 0L))) {
                    continue;
                }
                allocations.get(id).forEach((resource, count) -> work.merge(resource, count.longValue(), Long::sum));
                sequence.add(id);
                finished.add(id);
                progress = true;
            }
        } while (progress);

        List<String> stuck = needs.keySet().stream().filter(id -> !finished.contains(id)).toList();
        return new FinishResult(sequence, stuck, work);
    }
}
