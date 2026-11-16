package com.deadlocksim.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Scenario {
    private final String name;
    private final List<Resource> resources;
    private final List<Process> processes;
    private final Map<String, List<Operation>> operations;
    private PreventionToggles preventionToggles;
    private boolean bankersAvoidance;

    public Scenario(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Scenario name must not be null or blank");
        }
        this.name = name;
        this.resources = new ArrayList<>();
        this.processes = new ArrayList<>();
        this.operations = new LinkedHashMap<>();
        this.preventionToggles = new PreventionToggles();
    }

    public String getName() {
        return name;
    }

    public PreventionToggles getPreventionToggles() {
        return preventionToggles.copy();
    }

    public void setPreventionToggles(PreventionToggles t) {
        if (t == null) {
            throw new IllegalArgumentException("PreventionToggles must not be null");
        }
        this.preventionToggles = t.copy();
    }

    public boolean isBankersAvoidance() {
        return bankersAvoidance;
    }

    public void setBankersAvoidance(boolean enabled) {
        this.bankersAvoidance = enabled;
    }

    public boolean isShared(Resource resource) {
        return preventionToggles.isMutualExclusion() && resource.isShareable();
    }

    public Resource resource(String id) {
        return resources.stream().filter(r -> r.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown resource: " + id));
    }

    public void grant(Process process, Resource resource, int count) {
        process.checkHoldingCapacity(count);
        if (isShared(resource)) {
            if (count > resource.getTotal() - process.getHeld().getOrDefault(resource.getId(), 0)) {
                throw new IllegalArgumentException("Shared holding exceeds capacity of " + resource.getId());
            }
        } else if (!resource.grant(count)) {
            throw new IllegalStateException("Insufficient available instances of " + resource.getId());
        }
        process.holdInstances(resource.getId(), count);
    }

    public void release(Process process, Resource resource, int count) {
        if (count <= 0 || count > process.getHeld().getOrDefault(resource.getId(), 0)) {
            throw new IllegalArgumentException("Invalid release from " + resource.getId());
        }
        if (!isShared(resource)) {
            resource.release(count);
        }
        process.releaseInstances(resource.getId(), count);
    }

    public void releaseAll(Process process) {
        process.getHeld().forEach((id, count) -> release(process, resource(id), count));
    }

    public Map<String, Integer> maximumClaims(String processId) {
        Map<String, Integer> balance = new LinkedHashMap<>();
        Map<String, Integer> maximum = new LinkedHashMap<>();
        for (Operation operation : getOperations(processId)) {
            String resourceId = operation.getResourceId();
            long delta = operation.getType() == OperationType.REQUEST ? operation.getCount() : -operation.getCount();
            long held = balance.getOrDefault(resourceId, 0) + delta;
            if (held < 0 || held > resource(resourceId).getTotal()) {
                throw new IllegalArgumentException("Banker's avoidance requires a valid resource claim for "
                        + processId + " on " + resourceId);
            }
            balance.put(resourceId, (int) held);
            maximum.merge(resourceId, (int) held, Math::max);
        }
        return maximum;
    }

    public void addResource(Resource r) {
        if (r == null) {
            throw new IllegalArgumentException("Resource must not be null");
        }
        for (Resource existing : resources) {
            if (existing.getId().equals(r.getId())) {
                throw new IllegalArgumentException("Duplicate resource ID: " + r.getId());
            }
        }
        resources.add(r);
    }

    public void addProcess(Process p) {
        if (p == null) {
            throw new IllegalArgumentException("Process must not be null");
        }
        for (Process existing : processes) {
            if (existing.getId().equals(p.getId())) {
                throw new IllegalArgumentException("Duplicate process ID: " + p.getId());
            }
        }
        processes.add(p);
    }

    public void addOperation(Operation op) {
        if (op == null) {
            throw new IllegalArgumentException("Operation must not be null");
        }
        boolean processExists = processes.stream()
                .anyMatch(p -> p.getId().equals(op.getProcessId()));
        if (!processExists) {
            throw new IllegalArgumentException("Unknown process ID: " + op.getProcessId());
        }

        Resource targetResource = resources.stream()
                .filter(r -> r.getId().equals(op.getResourceId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown resource ID: " + op.getResourceId()));

        if (op.getType() == OperationType.REQUEST && op.getCount() > targetResource.getTotal()) {
            throw new IllegalArgumentException("Request count cannot exceed total instances of resource: " + targetResource.getId());
        }

        operations.computeIfAbsent(op.getProcessId(), k -> new ArrayList<>()).add(op);
    }

    public List<Resource> getResources() {
        return Collections.unmodifiableList(resources);
    }

    public List<Process> getProcesses() {
        return Collections.unmodifiableList(processes);
    }

    public List<Operation> getOperations(String processId) {
        List<Operation> list = operations.get(processId);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (processes.isEmpty()) {
            errors.add("Scenario must contain at least one process");
        }
        if (resources.isEmpty()) {
            errors.add("Scenario must contain at least one resource");
        }
        if (bankersAvoidance) {
            try {
                processes.forEach(process -> maximumClaims(process.getId()));
            } catch (IllegalArgumentException error) {
                errors.add(error.getMessage());
            }
        }
        return errors;
    }
}
