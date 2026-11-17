package com.deadlocksim.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public class Graph {
    private final Scenario scenario;

    public Graph(Scenario s) {
        if (s == null) {
            throw new IllegalArgumentException("Scenario must not be null");
        }
        this.scenario = s;
    }

    public enum EdgeKind {
        REQUEST,
        ALLOCATION
    }

    public enum NodeKind {
        PROCESS("process"), RESOURCE("resource");

        private final String name;

        NodeKind(String name) {
            this.name = name;
        }

        public String type() {
            return name;
        }
    }

    public record Node(NodeKind kind, String label) {
        private static final Comparator<Node> ORDER = Comparator.comparing(Node::label).thenComparing(Node::kind);

        public Node {
            Objects.requireNonNull(kind, "Node kind");
            if (label == null || label.isBlank()) throw new IllegalArgumentException("Node label must not be blank");
        }

        public String id() {
            return kind.type() + ":" + label;
        }
    }

    public record Edge(Node source, Node target, EdgeKind kind, int count) {
        public Edge(String from, String to, EdgeKind kind, int count) {
            this(new Node(kind == EdgeKind.REQUEST ? NodeKind.PROCESS : NodeKind.RESOURCE, from),
                    new Node(kind == EdgeKind.REQUEST ? NodeKind.RESOURCE : NodeKind.PROCESS, to), kind, count);
        }

        public Edge {
            if (kind == null || source == null || target == null || count <= 0) {
                throw new IllegalArgumentException("An edge requires endpoints, a kind, and a positive count");
            }
            NodeKind expectedSource = kind == EdgeKind.REQUEST ? NodeKind.PROCESS : NodeKind.RESOURCE;
            if (source.kind() != expectedSource || source.kind() == target.kind()) {
                throw new IllegalArgumentException("Edge endpoints do not match its kind");
            }
        }

        public String getFrom() { return source.label(); }
        public String getTo() { return target.label(); }
        public EdgeKind getKind() { return kind; }
        public int getCount() { return count; }
    }

    public Scenario getScenario() {
        return scenario;
    }

    public List<Node> nodes() {
        List<Node> nodes = new ArrayList<>();
        for (Process p : scenario.getProcesses()) nodes.add(new Node(NodeKind.PROCESS, p.getId()));
        for (Resource r : scenario.getResources()) nodes.add(new Node(NodeKind.RESOURCE, r.getId()));
        return List.copyOf(nodes);
    }

    public List<Edge> edges() {
        List<Edge> list = new ArrayList<>();
        for (Process p : scenario.getProcesses()) {
            if (p.isWaiting()) {
                p.getPendingRequest().demand().forEach((resourceId, count) -> {
                    if (!scenario.isShared(scenario.resource(resourceId))) {
                        list.add(new Edge(p.getId(), resourceId, EdgeKind.REQUEST, count));
                    }
                });
            }
        }
        for (Process p : scenario.getProcesses()) {
            for (Map.Entry<String, Integer> entry : p.getHeld().entrySet()) {
                list.add(new Edge(entry.getKey(), p.getId(), EdgeKind.ALLOCATION, entry.getValue()));
            }
        }
        return Collections.unmodifiableList(list);
    }

    public Set<Edge> cycleEdges() {
        return cycleEdges(edges());
    }

    public static Set<Edge> cycleEdges(List<Edge> edges) {
        Map<Node, List<Node>> adjacent = adjacency(edges);
        Set<Edge> cyclic = new LinkedHashSet<>();
        for (Edge edge : edges) {
            if (reaches(edge.target(), edge.source(), adjacent, new HashSet<>())) cyclic.add(edge);
        }
        return cyclic;
    }

    public static List<String> firstCycle(Set<Edge> cyclicEdges) {
        if (cyclicEdges.isEmpty()) return List.of();
        Map<Node, List<Node>> adjacent = adjacency(cyclicEdges);
        adjacent.values().forEach(nodes -> nodes.sort(Node.ORDER));
        Node start = adjacent.keySet().iterator().next();
        List<Node> cycle = new ArrayList<>();
        Set<Node> visited = new HashSet<>();
        Node current = start;
        do {
            cycle.add(current);
            visited.add(current);
            current = adjacent.get(current).stream()
                    .filter(next -> reaches(next, start, adjacent, new HashSet<>(visited)))
                    .findFirst().orElseThrow();
        } while (!current.equals(start));
        cycle.add(start);
        return cycle.stream().map(Node::label).toList();
    }

    private static Map<Node, List<Node>> adjacency(Iterable<Edge> edges) {
        Map<Node, List<Node>> adjacent = new TreeMap<>(Node.ORDER);
        for (Edge edge : edges) {
            adjacent.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge.target());
        }
        return adjacent;
    }

    private static boolean reaches(Node node, Node target, Map<Node, List<Node>> adjacent, Set<Node> visited) {
        if (node.equals(target)) return true;
        if (!visited.add(node)) return false;
        for (Node next : adjacent.getOrDefault(node, List.of())) {
            if (reaches(next, target, adjacent, visited)) return true;
        }
        return false;
    }

    public List<List<String>> findCycles() {
        Map<Node, List<Node>> adjacent = adjacency(edges());
        adjacent.values().forEach(nodes -> nodes.sort(Node.ORDER));
        List<List<String>> cycles = new ArrayList<>();
        for (Node start : adjacent.keySet()) {
            findCyclesDfs(start, start, adjacent, new LinkedHashSet<>(), cycles);
        }
        return Collections.unmodifiableList(cycles);
    }

    private void findCyclesDfs(Node start, Node current, Map<Node, List<Node>> adjacent,
                               Set<Node> path, List<List<String>> cycles) {
        path.add(current);
        for (Node neighbor : adjacent.getOrDefault(current, List.of())) {
            if (neighbor.equals(start)) {
                List<Node> cycle = new ArrayList<>(path);
                cycle.add(start);
                cycles.add(cycle.stream().map(Node::label).toList());
            } else if (!path.contains(neighbor) && Node.ORDER.compare(neighbor, start) > 0) {
                findCyclesDfs(start, neighbor, adjacent, path, cycles);
            }
        }
        path.remove(current);
    }
}
