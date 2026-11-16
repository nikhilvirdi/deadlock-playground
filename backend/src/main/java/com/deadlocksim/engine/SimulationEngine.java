package com.deadlocksim.engine;

import com.deadlocksim.model.EventType;
import com.deadlocksim.model.Graph;
import com.deadlocksim.model.Operation;
import com.deadlocksim.model.OperationType;
import com.deadlocksim.model.Process;
import com.deadlocksim.model.Process.PendingRequest;
import com.deadlocksim.model.ProcessState;
import com.deadlocksim.model.Resource;
import com.deadlocksim.model.SafetyChecker;
import com.deadlocksim.model.SafetyResult;
import com.deadlocksim.model.Scenario;
import com.deadlocksim.model.Snapshot;
import com.deadlocksim.model.detection.BankersDetection;
import com.deadlocksim.model.detection.CycleDetection;
import com.deadlocksim.model.detection.DetectionResult;
import com.deadlocksim.model.history.History;
import com.deadlocksim.model.history.HistoryEvent;
import com.deadlocksim.model.recovery.Preempt;
import com.deadlocksim.model.recovery.Terminate;
import com.deadlocksim.model.victim.VictimPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.deadlocksim.engine.SimulationException.Kind.*;

public class SimulationEngine {
    private Scenario scenario;
    final History history;
    private Snapshot initialSnapshot;
    private int viewedSeq;

    public SimulationEngine() {
        this.scenario = null;
        this.history = new History();
        this.initialSnapshot = null;
        this.viewedSeq = 0;
    }

    private void updateViewedSeq() {
        if (this.history.size() > 0) {
            this.viewedSeq = this.history.events().get(this.history.size() - 1).getSequence();
        }
    }

    private void checkTruncateBeforeAction() {
        if (this.history.size() > 0) {
            int newestSeq = this.history.events().get(this.history.size() - 1).getSequence();
            if (this.viewedSeq < newestSeq) {
                this.history.truncateAfter(this.viewedSeq);
            }
        }
    }

    public int getViewedSeq() {
        return viewedSeq;
    }

    public void configure(Scenario s) {
        if (s == null) {
            throw new IllegalArgumentException("Scenario must not be null");
        }
        List<String> errors = s.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        this.scenario = s;
        this.initialSnapshot = Snapshot.capture(s);
        this.history.clear();
        this.history.add(EventType.STATE_CHANGE, "Scenario loaded", this.initialSnapshot);
        updateViewedSeq();
    }

    public void reset(boolean confirmed) {
        if (this.scenario == null) {
            throw new SimulationException(NO_SCENARIO, "No scenario loaded");
        }
        if (this.history.size() > 1 && !confirmed) {
            throw new SimulationException(VALIDATION_ERROR, "Confirmation required to reset");
        }

        this.scenario = null;
        this.initialSnapshot = null;
        this.history.clear();
        this.viewedSeq = 0;
    }

    public RequestResult request(String processId, String resourceId, int count) {
        Process process = requireProcess(processId);
        return request(process, new PendingRequest(resourceId, count, Map.of(resourceId, count), -1, 0));
    }

    private RequestResult request(Process process, PendingRequest request) {
        if (process.getState() != ProcessState.RUNNING) {
            throw new SimulationException(VALIDATION_ERROR, "Process is not RUNNING: " + process.getId());
        }
        process.checkHoldingCapacity(request.count());
        if (request.count() > scenario.resource(request.resourceId()).getTotal()
                && request.operationCount() <= 1) {
            throw new IllegalArgumentException("Request count cannot exceed total instances of resource: " + request.resourceId());
        }

        var toggles = scenario.getPreventionToggles();
        Map<String, Integer> held = process.getHeld();
        if (toggles.isHoldAndWait() && !held.isEmpty()) {
            return rejectPrevention(process, request.operationIndex(), "Hold and wait prevention: release all holdings before starting another request group");
        }
        if (toggles.isCircularWait() && violatesOrder(process, held, request)) {
            return rejectPrevention(process, request.operationIndex(), "Circular wait prevention: request resources in scenario order");
        }

        Map<String, Integer> claims = scenario.isBankersAvoidance() ? scenario.maximumClaims(process.getId()) : Map.of();
        long demandCount = 0;
        for (var demand : request.demand().entrySet()) {
            Resource resource = scenario.resource(demand.getKey());
            int count = demand.getValue();
            demandCount += count;
            long after = (long) held.getOrDefault(resource.getId(), 0) + count;
            if (scenario.isBankersAvoidance() && after > claims.getOrDefault(resource.getId(), 0)) {
                throw new SimulationException(VALIDATION_ERROR, "Request exceeds the declared claim of " + process.getId()
                        + " for " + resource.getId() + ". Declare the demand in the script or use demo mode.");
            }
            if (scenario.isShared(resource) && after > resource.getTotal() && request.operationCount() == 0) {
                throw new IllegalArgumentException("Shared holding exceeds capacity of " + resource.getId());
            }
            if (count > resource.getTotal() || (scenario.isShared(resource) && after > resource.getTotal())) {
                return rejectPrevention(process, request.operationIndex(), "Request group exceeds capacity of " + resource.getId());
            }
        }
        if (demandCount > Integer.MAX_VALUE - process.totalHeld()) {
            throw new IllegalArgumentException("Total instances held by a process cannot exceed " + Integer.MAX_VALUE);
        }

        String unavailable = unavailableResource(request);
        if (unavailable != null) {
            boolean returnHoldings = toggles.isNoPreemption() && !held.isEmpty();
            if (returnHoldings) {
                Map<String, Integer> combined = new LinkedHashMap<>(held);
                for (var demand : request.demand().entrySet()) {
                    long total = combined.getOrDefault(demand.getKey(), 0) + (long) demand.getValue();
                    if (total > scenario.resource(demand.getKey()).getTotal()) {
                        return rejectPrevention(process, request.operationIndex(),
                                "Release when blocked: cannot reacquire more than the capacity of " + demand.getKey());
                    }
                    combined.put(demand.getKey(), (int) total);
                }
                request = new PendingRequest(unavailable, combined.get(unavailable), combined,
                        request.operationIndex(), request.operationCount());
            } else {
                request = new PendingRequest(unavailable, request.demand().get(unavailable), request.demand(),
                        request.operationIndex(), request.operationCount());
            }

            checkTruncateBeforeAction();
            if (returnHoldings) scenario.releaseAll(process);
            queue(process, request);
            String description = "Process " + process.getId() + " blocked waiting for " + describeDemand(request);
            if (returnHoldings) description += " after returning its holdings";
            record(EventType.BLOCK, description);
            if (returnHoldings) wakeWaitingProcesses();
            updateViewedSeq();
            if (!process.isWaiting()) {
                return new RequestResult(RequestOutcome.GRANTED, "Granted " + describeDemand(request) + " to " + process.getId());
            }
            return new RequestResult(RequestOutcome.BLOCKED, description);
        }

        SafetyResult safety = scenario.isBankersAvoidance() ? SafetyChecker.check(scenario, process, request.demand()) : null;
        checkTruncateBeforeAction();
        if (safety != null && !safety.isSafe()) {
            queue(process, request);
            record(EventType.AVOIDANCE_CHECK, safety.getReason());
            updateViewedSeq();
            return new RequestResult(RequestOutcome.DENIED_UNSAFE, safety.getReason(), safety);
        }

        String description = "Granted " + describeDemand(request) + " to " + process.getId();
        grant(process, request, description, safety);
        if (process.isTerminal()) wakeWaitingProcesses();
        updateViewedSeq();
        return new RequestResult(RequestOutcome.GRANTED, description, safety);
    }

    private boolean violatesOrder(Process process, Map<String, Integer> held, PendingRequest request) {
        List<Resource> resources = scenario.getResources();
        int highest = held.keySet().stream().mapToInt(id -> resources.indexOf(scenario.resource(id))).max().orElse(-1);
        if (request.operationCount() > 1) {
            // Check the original order, including repeated requests within a group.
            List<Operation> ops = scenario.getOperations(process.getId());
            for (int i = request.operationIndex(); i < request.operationIndex() + request.operationCount(); i++) {
                int rank = resources.indexOf(scenario.resource(ops.get(i).getResourceId()));
                if (rank <= highest) return true;
                highest = rank;
            }
            return false;
        }
        return resources.indexOf(scenario.resource(request.resourceId())) <= highest;
    }

    private RequestResult rejectPrevention(Process process, int operationIndex, String reason) {
        checkTruncateBeforeAction();
        if (operationIndex >= 0) {
            new Terminate().apply(scenario, process);
            reason += ". Stopped " + process.getId() + "'s script and released its holdings";
        }
        record(EventType.BLOCK, reason);
        if (process.isTerminal()) wakeWaitingProcesses();
        updateViewedSeq();
        return new RequestResult(RequestOutcome.REJECTED_PREVENTION, reason);
    }

    private static String describeDemand(PendingRequest request) {
        return String.join(", ", request.demand().entrySet().stream()
                .map(entry -> entry.getValue() + " of " + entry.getKey()).toList());
    }

    private String unavailableResource(PendingRequest request) {
        for (var demand : request.demand().entrySet()) {
            Resource resource = scenario.resource(demand.getKey());
            if (!scenario.isShared(resource) && demand.getValue() > resource.getAvailable()) return resource.getId();
        }
        return null;
    }

    private static void queue(Process process, PendingRequest request) {
        process.setState(ProcessState.WAITING);
        process.setPendingRequest(request);
    }

    private void grant(Process process, PendingRequest request, String description, SafetyResult safety) {
        if (request.operationCount() > 0 && process.getNextOperationIndex() != request.operationIndex()) {
            throw new IllegalStateException("Pending operation no longer matches the script cursor");
        }
        request.demand().forEach((id, count) -> scenario.grant(process, scenario.resource(id), count));
        process.clearWaitingFor();
        process.setState(ProcessState.RUNNING);
        process.setDeadlocked(false);
        boolean completed = finishOperations(process, request.operationCount());
        if (safety != null) record(EventType.AVOIDANCE_CHECK, safety.getReason());
        record(EventType.GRANT, description);
        if (completed) record(EventType.STATE_CHANGE, process.getId() + " completed and released all holdings");
    }

    public ReleaseResult release(String processId, String resourceId, int count) {
        return release(requireProcess(processId), resourceId, count, false);
    }

    private ReleaseResult release(Process process, String resourceId, int count, boolean scripted) {
        if (count <= 0) throw new IllegalArgumentException("Count must be greater than zero");
        Resource resource = scenario.resource(resourceId);
        int held = process.getHeld().getOrDefault(resourceId, 0);
        if (held < count) {
            throw new SimulationException(NOT_HELD, "Process " + process.getId() + " holds only " + held + " of " + resourceId);
        }

        checkTruncateBeforeAction();
        scenario.release(process, resource, count);
        boolean completed = finishOperations(process, scripted ? 1 : 0);
        record(EventType.RELEASE, "Process " + process.getId() + " released " + count + " of " + resourceId);
        if (completed) record(EventType.STATE_CHANGE, process.getId() + " completed and released all holdings");
        List<String> unblocked = wakeWaitingProcesses();
        updateViewedSeq();
        return new ReleaseResult(unblocked);
    }

    List<String> wakeWaitingProcesses() {
        List<String> unblocked = new ArrayList<>();
        boolean completed;
        do {
            completed = false;
            for (Process process : scenario.getProcesses()) {
                if (process.getState() != ProcessState.WAITING || !process.isWaiting()) continue;
                PendingRequest pending = process.getPendingRequest();
                if (unavailableResource(pending) != null) continue;
                if (scenario.isBankersAvoidance() && !SafetyChecker.check(scenario, process, pending.demand()).isSafe()) continue;

                grant(process, pending, "Granted " + describeDemand(pending) + " to waiting process " + process.getId(), null);
                unblocked.add(process.getId());
                completed |= process.isTerminal();
            }
        } while (completed);
        return unblocked;
    }

    private boolean finishOperations(Process process, int count) {
        if (count == 0) return false;
        for (int i = 0; i < count; i++) {
            process.advanceOperation();
            process.recordInstruction();
        }
        if (hasPendingOperations(process)) return false;
        scenario.releaseAll(process);
        process.setState(ProcessState.COMPLETED);
        return true;
    }

    private boolean hasPendingOperations(Process process) {
        return !process.isTerminal() && process.getNextOperationIndex() < scenario.getOperations(process.getId()).size();
    }

    public StepResult step() {
        requireScenario();
        for (Process process : scenario.getProcesses()) {
            if (process.getState() != ProcessState.RUNNING || !hasPendingOperations(process)) continue;
            String description = executeOperation(process);
            return new StepResult(description, scenario.getProcesses().stream().noneMatch(this::hasPendingOperations));
        }
        return scenario.getProcesses().stream().noneMatch(this::hasPendingOperations)
                ? new StepResult("Scenario finished", true)
                : new StepResult("No runnable process; deadlock or waiting", false);
    }

    private String executeOperation(Process process) {
        List<Operation> operations = scenario.getOperations(process.getId());
        int index = process.getNextOperationIndex();
        Operation op = operations.get(index);
        if (op.getType() == OperationType.RELEASE) {
            release(process, op.getResourceId(), op.getCount(), true);
            return process.getId() + " released " + op.getCount() + " of " + op.getResourceId();
        }

        Map<String, Integer> demand = new LinkedHashMap<>();
        demand.put(op.getResourceId(), op.getCount());
        int end = index + 1;
        if (scenario.getPreventionToggles().isHoldAndWait()) {
            while (end < operations.size() && operations.get(end).getType() == OperationType.REQUEST) {
                Operation next = operations.get(end++);
                String id = next.getResourceId();
                int previous = demand.getOrDefault(id, 0);
                if (next.getCount() > scenario.resource(id).getTotal() - previous) {
                    return rejectPrevention(process, index, "Request group exceeds capacity of " + id).reason()
                            + ": REJECTED_PREVENTION";
                }
                demand.put(id, previous + next.getCount());
            }
        }
        PendingRequest pending = new PendingRequest(op.getResourceId(), op.getCount(), demand, index, end - index);
        RequestResult result = request(process, pending);
        return switch (result.outcome()) {
            case GRANTED -> result.reason();
            case REJECTED_PREVENTION -> result.reason() + ": REJECTED_PREVENTION";
            default -> process.getId() + " request (" + describeDemand(pending)
                    + "): " + result.outcome();
        };
    }

    private void requireScenario() {
        if (scenario == null) throw new SimulationException(NO_SCENARIO, "No scenario loaded");
    }

    private Process requireProcess(String processId) {
        requireScenario();
        return scenario.getProcesses().stream().filter(p -> p.getId().equals(processId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown process: " + processId));
    }

    private void record(EventType type, String description) {
        history.add(type, description, Snapshot.capture(scenario));
    }

    public DetectReport detect() {
        requireScenario();
        if (scenario.getProcesses().stream().noneMatch(Process::isWaiting)) {
            throw new SimulationException(NO_DEADLOCK, "No waiting process to check");
        }
        checkTruncateBeforeAction();
        DetectReport report = detectCurrentState();
        updateViewedSeq();
        return report;
    }

    private DetectReport detectCurrentState() {
        boolean allSingle = scenario.getResources().stream().allMatch(r -> r.getTotal() == 1);
        Graph graph = new Graph(scenario);
        DetectionResult result = allSingle ? new CycleDetection().detect(graph) : new BankersDetection().detect(graph);
        record(EventType.DETECT, result.isDeadlocked() ? "Deadlock detected: " + result.getProcessIds() : "No deadlock detected");
        return new DetectReport(allSingle ? "CYCLE" : "BANKERS", result);
    }

    public RecoveryReport recover(VictimPolicy policy, boolean usePreempt) {
        requireScenario();
        if (policy == null) throw new IllegalArgumentException("Victim policy must not be null");
        DetectionResult current = detect().result();
        if (!current.isDeadlocked()) throw new SimulationException(NO_DEADLOCK, "No deadlock to recover from");

        List<RecoveryRound> rounds = new ArrayList<>();
        while (current.isDeadlocked()) {
            List<Process> eligible = new ArrayList<>();
            for (Process process : scenario.getProcesses()) {
                if (current.recoveryResource(process.getId()) != null) eligible.add(process);
            }
            if (eligible.isEmpty()) break;

            Process victim = policy.selectVictim(eligible);
            if (!eligible.contains(victim)) break;
            record(EventType.VICTIM_SELECTED, "Victim selected: " + victim.getId());
            String strategy;
            if (usePreempt) {
                strategy = "PREEMPT";
                String resource = current.recoveryResource(victim.getId());
                new Preempt(resource).apply(scenario, victim);
                record(EventType.PREEMPT, "Preempted resource " + resource + " from victim " + victim.getId());
                record(EventType.ROLLBACK, "Rolled back victim " + victim.getId());
            } else {
                strategy = "TERMINATE";
                new Terminate().apply(scenario, victim);
                record(EventType.TERMINATE, "Terminated victim " + victim.getId());
            }

            wakeWaitingProcesses();
            current = detectCurrentState().result();
            rounds.add(new RecoveryRound(victim.getId(), strategy, !current.isDeadlocked()));
        }
        record(EventType.RESOLVED, current.isDeadlocked() ? "Recovery failed" : "Deadlock resolved");
        updateViewedSeq();
        return new RecoveryReport(rounds, !current.isDeadlocked());
    }

    public Scenario getScenario() {
        return scenario;
    }

    public List<HistoryEvent> getHistoryEvents() {
        return history.events();
    }

    public void jumpTo(int seq) {
        if (scenario == null) {
            throw new SimulationException(NO_SCENARIO, "No scenario loaded");
        }
        Snapshot snapshot = history.jumpTo(seq);
        snapshot.restoreInto(scenario);
        this.viewedSeq = seq;
    }
}
