package com.deadlocksim.api;

import com.deadlocksim.engine.SimulationEngine;
import com.deadlocksim.model.Process;
import com.deadlocksim.model.Resource;
import com.deadlocksim.model.Scenario;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GraphConsistencyTest {
    @Test
    void identicalLabelsAndEdgeLikeLabelsRemainDistinctAcrossGraphAndDetection() {
        String special = "edge:1 / '\u03a9'";
        Scenario scenario = new Scenario("Colliding user labels");
        scenario.addProcess(new Process("X", 1));
        scenario.addProcess(new Process("e1", 2));
        scenario.addResource(new Resource("X", 1, false));
        scenario.addResource(new Resource(special, 1, false));
        SimulationEngine engine = configure(scenario);
        engine.request("X", "X", 1);
        engine.request("e1", special, 1);
        engine.request("e1", "X", 1);
        assertFalse(engine.detect().result().isDeadlocked());
        engine.request("X", special, 1);
        assertEquals(List.of("X", "e1"), engine.detect().result().getProcessIds());

        var graph = graph(engine);
        var nodes = graph.getAsJsonArray("nodes");
        var edges = graph.getAsJsonArray("edges");
        assertEquals(4, nodes.size());
        assertEquals(4, edges.size());
        Set<String> ids = new HashSet<>();
        for (var node : nodes) assertTrue(ids.add(element(node.getAsJsonObject()).get("id").getAsString()));
        assertTrue(ids.containsAll(Set.of("process:X", "resource:X", "process:e1", "resource:" + special)));
        for (var edge : edges) assertTrue(ids.add(element(edge.getAsJsonObject()).get("id").getAsString()));
        assertEquals("X", vertex(nodes, "process:X").get("label").getAsString());
        assertEquals("X", vertex(nodes, "resource:X").get("label").getAsString());
        assertEquals(special, vertex(nodes, "resource:" + special).get("label").getAsString());
        assertEdge(edges, "allocation", "resource:X", "process:X", 1);
        assertEdge(edges, "request", "process:X", "resource:" + special, 1);
        assertEdge(edges, "request", "process:e1", "resource:X", 1);
        assertEdge(edges, "allocation", "resource:" + special, "process:e1", 1);
        assertEquals(4, flagged(edges, "deadlocked"));
    }

    @Test
    void aMultiInstanceStructuralCycleCanBeSafe() {
        Scenario scenario = twoResources(2);
        scenario.addProcess(new Process("Finisher", 3));
        SimulationEngine engine = configure(scenario);
        engine.request("P", "A", 1);
        engine.request("Q", "B", 1);
        engine.request("Finisher", "A", 1);
        engine.request("Finisher", "B", 1);
        engine.request("P", "B", 1);
        engine.request("Q", "A", 1);
        assertFalse(engine.detect().result().isDeadlocked());
        assertEquals(4, flagged(graph(engine).getAsJsonArray("edges"), "inCycle"));
        assertEquals(0, flagged(graph(engine).getAsJsonArray("edges"), "deadlocked"));
        assertEquals(0, flagged(graph(engine).getAsJsonArray("nodes"), "deadlocked"));
    }

    @Test
    void confirmedMultiInstanceDeadlockClearsAfterReleaseAndRestoresWithItsSnapshot() {
        SimulationEngine engine = configure(twoResources(2));
        engine.request("P", "A", 2);
        engine.request("Q", "B", 2);
        engine.request("P", "B", 1);
        engine.request("Q", "A", 1);
        assertEquals(List.of("P", "Q"), engine.detect().result().getProcessIds());
        int confirmed = engine.getViewedSeq();
        JsonObject before = graph(engine);
        assertEquals(2, flagged(before.getAsJsonArray("nodes"), "deadlocked"));

        assertEquals(List.of("Q"), engine.release("P", "A", 1).unblockedProcessIds());
        int resolved = engine.getViewedSeq();
        JsonObject after = graph(engine);
        assertEquals(0, flagged(after.getAsJsonArray("edges"), "inCycle"));
        assertEquals(0, flagged(after.getAsJsonArray("nodes"), "deadlocked"));
        assertTrue(engine.getScenario().getProcesses().stream().noneMatch(Process::isDeadlocked));

        engine.jumpTo(confirmed);
        assertEquals(before, graph(engine));
        engine.jumpTo(resolved);
        assertEquals(after, graph(engine));
    }

    @Test
    void explicitRecoveryClearsConfirmedGraphFlagsAndHistoryCanRestoreThem() {
        SimulationEngine engine = configure(twoResources(1));
        engine.request("P", "A", 1);
        engine.request("Q", "B", 1);
        engine.request("P", "B", 1);
        engine.request("Q", "A", 1);
        engine.detect();
        int detected = engine.getViewedSeq();
        assertTrue(engine.recover(eligible -> eligible.get(0), true).resolved());
        assertEquals(0, flagged(graph(engine).getAsJsonArray("nodes"), "deadlocked"));
        assertEquals(0, flagged(graph(engine).getAsJsonArray("edges"), "deadlocked"));
        engine.jumpTo(detected);
        assertEquals(4, flagged(graph(engine).getAsJsonArray("edges"), "deadlocked"));
    }

    private static SimulationEngine configure(Scenario scenario) {
        var engine = new SimulationEngine();
        engine.configure(scenario);
        return engine;
    }

    private static Scenario twoResources(int count) {
        var scenario = new Scenario("Graph states");
        scenario.addResource(new Resource("A", count, false));
        scenario.addResource(new Resource("B", count, false));
        scenario.addProcess(new Process("P", 1));
        scenario.addProcess(new Process("Q", 2));
        return scenario;
    }

    private static JsonObject graph(SimulationEngine engine) {
        return JsonMapper.mapState(engine).getAsJsonObject("graph");
    }

    private static JsonObject element(JsonObject object) {
        return object.getAsJsonObject("data");
    }

    private static JsonObject vertex(JsonArray nodes, String id) {
        return nodes.asList().stream().map(value -> element(value.getAsJsonObject()))
                .filter(data -> data.get("id").getAsString().equals(id)).findFirst().orElseThrow();
    }

    private static long flagged(JsonArray elements, String flag) {
        return elements.asList().stream().map(value -> element(value.getAsJsonObject()))
                .filter(data -> data.has(flag) && data.get(flag).getAsBoolean()).count();
    }

    private static void assertEdge(JsonArray edges, String kind, String source, String target, int count) {
        assertTrue(edges.asList().stream().map(value -> element(value.getAsJsonObject()))
                .anyMatch(data -> data.get("kind").getAsString().equals(kind)
                        && data.get("source").getAsString().equals(source)
                        && data.get("target").getAsString().equals(target)
                        && data.get("count").getAsInt() == count));
    }
}
