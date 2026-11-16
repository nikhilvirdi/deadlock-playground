package com.deadlocksim.model.detection;

import com.deadlocksim.model.Graph;
import com.deadlocksim.model.Process;
import com.deadlocksim.model.Resource;
import com.deadlocksim.model.Scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;

public class CycleDetection implements DetectionStrategy {

    @Override
    public DetectionResult detect(Graph g) {
        if (g == null) {
            throw new IllegalArgumentException("Graph must not be null");
        }
        Scenario scenario = g.getScenario();
        for (Resource resource : scenario.getResources()) {
            if (resource.getTotal() > 1) {
                throw new IllegalStateException("CycleDetection is only valid for single-instance resources, but "
                        + resource.getId() + " has total " + resource.getTotal());
            }
        }

        Set<Graph.Edge> cyclicEdges = g.cycleEdges();
        if (cyclicEdges.isEmpty()) {
            for (Process process : scenario.getProcesses()) {
                process.setDeadlocked(false);
            }
            return DetectionResult.none();
        }

        Map<String, String> recoveryResources = new HashMap<>();
        for (Graph.Edge edge : cyclicEdges) {
            if (edge.getKind() == Graph.EdgeKind.ALLOCATION) {
                recoveryResources.merge(edge.getTo(), edge.getFrom(), BinaryOperator.minBy(String::compareTo));
            }
        }

        for (Process process : scenario.getProcesses()) {
            process.setDeadlocked(recoveryResources.containsKey(process.getId()));
        }

        return DetectionResult.of(new ArrayList<>(recoveryResources.keySet()), Graph.firstCycle(cyclicEdges), recoveryResources);
    }

}
