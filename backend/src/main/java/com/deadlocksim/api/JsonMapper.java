package com.deadlocksim.api;

import com.deadlocksim.engine.SimulationEngine;
import com.deadlocksim.engine.SimulationException;
import com.deadlocksim.model.Graph;
import com.deadlocksim.model.Operation;
import com.deadlocksim.model.OperationType;
import com.deadlocksim.model.PreventionToggles;
import com.deadlocksim.model.Process;
import com.deadlocksim.model.Resource;
import com.deadlocksim.model.Scenario;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static com.deadlocksim.api.ApiInput.*;

public class JsonMapper {
    private static final Gson GSON = new Gson();

    public static Gson getGson() {
        return GSON;
    }

    public static Scenario parseScenario(JsonObject body) {
        if (body == null) {
            throw new IllegalArgumentException("Request body must not be null");
        }
        if (body.has("preset")) {
            throw new IllegalArgumentException("Preset not available yet");
        }
        Scenario s = new Scenario(text(body, "name"));
        s.setBankersAvoidance(flag(body, "bankersAvoidance"));

        if (body.has("prevention")) {
            JsonObject prev = object(body.get("prevention"), "prevention");
            PreventionToggles toggles = s.getPreventionToggles();
            toggles.setMutualExclusion(flag(prev, "mutualExclusion"));
            toggles.setHoldAndWait(flag(prev, "holdAndWait"));
            toggles.setNoPreemption(flag(prev, "noPreemption"));
            toggles.setCircularWait(flag(prev, "circularWait"));
            s.setPreventionToggles(toggles);
        }

        for (JsonElement el : array(body, "resources", MAX_RESOURCES)) {
            JsonObject resource = object(el, "resources");
            s.addResource(new Resource(text(resource, "id"), integer(resource, "total", 1, MAX_INSTANCES),
                    flag(resource, "shareable")));
        }

        int remainingOperations = MAX_OPERATIONS;
        for (JsonElement el : array(body, "processes", MAX_PROCESSES)) {
            JsonObject process = object(el, "processes");
            String id = text(process, "id");
            int priority = process.has("priority")
                    ? integer(process, "priority", Integer.MIN_VALUE, Integer.MAX_VALUE) : 1;
            s.addProcess(new Process(id, priority));
            if (!process.has("operations")) continue;
            JsonArray operations = array(process, "operations", remainingOperations);
            remainingOperations -= operations.size();
            for (JsonElement item : operations) {
                JsonObject operation = object(item, "operations");
                OperationType type = switch (text(operation, "type").toUpperCase(Locale.ROOT)) {
                    case "REQUEST" -> OperationType.REQUEST;
                    case "RELEASE" -> OperationType.RELEASE;
                    default -> throw new IllegalArgumentException("Operation type must be REQUEST or RELEASE");
                };
                int count = operation.has("count") ? integer(operation, "count", 1, Integer.MAX_VALUE) : 1;
                s.addOperation(new Operation(id, type, text(operation, "resource"), count));
            }
        }

        return s;
    }

    public static JsonObject mapState(SimulationEngine engine) {
        Scenario scenario = engine == null ? null : engine.getScenario();
        if (scenario == null) {
            throw new SimulationException(SimulationException.Kind.NO_SCENARIO, "No scenario loaded");
        }
        JsonObject root = new JsonObject();

        JsonObject scenarioObj = new JsonObject();
        scenarioObj.addProperty("name", scenario.getName());
        scenarioObj.addProperty("bankersAvoidance", scenario.isBankersAvoidance());
        scenarioObj.addProperty("configurationRevision", engine.getConfigurationRevision());
        root.add("scenario", scenarioObj);

        root.addProperty("step", engine.getViewedSeq());

        JsonObject prevObj = new JsonObject();
        PreventionToggles toggles = scenario.getPreventionToggles();
        prevObj.addProperty("mutualExclusion", toggles.isMutualExclusion());
        prevObj.addProperty("holdAndWait", toggles.isHoldAndWait());
        prevObj.addProperty("noPreemption", toggles.isNoPreemption());
        prevObj.addProperty("circularWait", toggles.isCircularWait());
        root.add("prevention", prevObj);

        JsonArray processesArray = new JsonArray();
        for (Process p : scenario.getProcesses()) {
            JsonObject procObj = new JsonObject();
            procObj.addProperty("id", p.getId());
            procObj.addProperty("priority", p.getPriority());
            procObj.addProperty("state", p.getState().name());
            procObj.addProperty("deadlocked", p.isDeadlocked());

            JsonArray heldArray = new JsonArray();
            List<String> heldKeys = new ArrayList<>(p.getHeld().keySet());
            Collections.sort(heldKeys);
            for (String resId : heldKeys) {
                int count = p.getHeld().getOrDefault(resId, 0);
                if (count > 0) {
                    JsonObject h = new JsonObject();
                    h.addProperty("resource", resId);
                    h.addProperty("count", count);
                    heldArray.add(h);
                }
            }
            procObj.add("held", heldArray);

            if (p.isWaiting()) {
                JsonObject wf = new JsonObject();
                wf.addProperty("resource", p.getWaitingResourceId());
                wf.addProperty("count", p.getWaitingCount());
                procObj.add("waitingFor", wf);
            } else {
                procObj.add("waitingFor", JsonNull.INSTANCE);
            }

            JsonArray operations = new JsonArray();
            for (Operation operation : scenario.getOperations(p.getId())) {
                JsonObject item = new JsonObject();
                item.addProperty("type", operation.getType().name());
                item.addProperty("resource", operation.getResourceId());
                item.addProperty("count", operation.getCount());
                operations.add(item);
            }
            procObj.add("operations", operations);

            int pending = p.isTerminal() ? 0 : Math.max(0, scenario.getOperations(p.getId()).size() - p.getNextOperationIndex());
            procObj.addProperty("pendingOperations", pending);

            processesArray.add(procObj);
        }
        root.add("processes", processesArray);

        JsonArray resourcesArray = new JsonArray();
        for (Resource r : scenario.getResources()) {
            JsonObject resObj = new JsonObject();
            resObj.addProperty("id", r.getId());
            resObj.addProperty("name", r.getName());
            resObj.addProperty("total", r.getTotal());
            resObj.addProperty("available", r.getAvailable());
            resObj.addProperty("shareable", r.isShareable());
            resourcesArray.add(resObj);
        }
        root.add("resources", resourcesArray);

        Graph graph = new Graph(scenario);
        List<Graph.Edge> edges = graph.edges();
        Set<Graph.Edge> cycleEdges = Graph.cycleEdges(edges);
        Set<String> detected = new HashSet<>();
        for (Process process : scenario.getProcesses()) {
            if (process.isDeadlocked()) detected.add(process.getId());
        }
        boolean singleInstance = scenario.getResources().stream().allMatch(resource -> resource.getTotal() == 1);

        JsonObject graphObj = new JsonObject();
        JsonArray nodesArray = new JsonArray();

        for (Graph.Node vertex : graph.nodes()) {
            JsonObject node = new JsonObject();
            JsonObject data = new JsonObject();
            data.addProperty("id", vertex.id());
            data.addProperty("label", vertex.label());
            data.addProperty("type", vertex.kind().type());
            if (vertex.kind() == Graph.NodeKind.PROCESS) {
                data.addProperty("deadlocked", detected.contains(vertex.label()));
            } else {
                data.addProperty("instances", scenario.resource(vertex.label()).getTotal());
            }
            node.add("data", data);
            nodesArray.add(node);
        }
        graphObj.add("nodes", nodesArray);

        JsonArray edgesArray = new JsonArray();
        int edgeSeq = 1;
        for (Graph.Edge edge : edges) {
            JsonObject edgeObj = new JsonObject();
            JsonObject data = new JsonObject();
            data.addProperty("id", "edge:" + edgeSeq++);
            data.addProperty("source", edge.source().id());
            data.addProperty("target", edge.target().id());
            data.addProperty("kind", edge.getKind().name().toLowerCase(Locale.ROOT));
            boolean inCycle = cycleEdges.contains(edge);
            data.addProperty("count", edge.getCount());
            data.addProperty("inCycle", inCycle);
            Graph.Node process = edge.getKind() == Graph.EdgeKind.REQUEST ? edge.source() : edge.target();
            data.addProperty("deadlocked", singleInstance && inCycle && detected.contains(process.label()));
            edgeObj.add("data", data);
            edgesArray.add(edgeObj);
        }
        graphObj.add("edges", edgesArray);

        root.add("graph", graphObj);

        return root;
    }
}
