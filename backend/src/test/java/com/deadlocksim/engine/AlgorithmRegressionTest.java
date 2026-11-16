package com.deadlocksim.engine;

import com.deadlocksim.api.JsonMapper;
import com.deadlocksim.model.EventType;
import com.deadlocksim.model.Operation;
import com.deadlocksim.model.OperationType;
import com.deadlocksim.model.Process;
import com.deadlocksim.model.ProcessState;
import com.deadlocksim.model.Resource;
import com.deadlocksim.model.Scenario;
import com.deadlocksim.model.victim.PriorityPolicy;
import com.deadlocksim.model.victim.RecoveryCostPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static com.deadlocksim.model.OperationType.RELEASE;
import static com.deadlocksim.model.OperationType.REQUEST;
import static org.junit.jupiter.api.Assertions.*;

class AlgorithmRegressionTest {

    @Test
    void manualWaitAfterSnapshotRestoreDoesNotConsumeTheScriptedRequest() {
        Scenario scenario = new Scenario("Manual wait");
        Process waiter = process(scenario, "Waiter");
        process(scenario, "Holder");
        scenario.addResource(new Resource("A", 1, false));
        scenario.addResource(new Resource("B", 1, false));
        op(scenario, waiter, REQUEST, "B");
        op(scenario, waiter, RELEASE, "B");
        SimulationEngine engine = load(scenario);

        assertEquals(RequestOutcome.GRANTED, engine.request("Holder", "A", 1).outcome());
        assertEquals(RequestOutcome.BLOCKED, engine.request("Waiter", "A", 1).outcome());
        int waiting = engine.getViewedSeq();
        assertEquals(List.of("Waiter"), engine.release("Holder", "A", 1).unblockedProcessIds());
        engine.jumpTo(waiting);
        assertEquals(List.of("Waiter"), engine.release("Holder", "A", 1).unblockedProcessIds());

        assertEquals(0, waiter.getNextOperationIndex());
        assertEquals(0, waiter.getInstructionsExecuted());
        assertFalse(engine.step().finished());
        assertEquals(Map.of("A", 1, "B", 1), waiter.getHeld());
        assertTrue(engine.step().finished());
        assertEquals(ProcessState.COMPLETED, waiter.getState());
        assertEquals(2, waiter.getInstructionsExecuted());
        assertThrows(SimulationException.class, () -> engine.request("Waiter", "B", 1));
        assertAccounting(scenario);
    }

    @Test
    void completingWhileWakingReturnsHoldingsAndRechecksEarlierWaiters() {
        Scenario scenario = new Scenario("Wake cascade");
        Process first = process(scenario, "First");
        Process second = process(scenario, "Second");
        process(scenario, "Holder");
        scenario.addResource(new Resource("A", 1, false));
        scenario.addResource(new Resource("B", 1, false));
        op(scenario, first, REQUEST, "A");
        op(scenario, second, REQUEST, "B");
        SimulationEngine engine = load(scenario);

        engine.request("Second", "A", 1);
        engine.request("Holder", "B", 1);
        assertFalse(engine.step().finished());
        assertFalse(engine.step().finished());
        int blocked = engine.getViewedSeq();
        assertEquals(0, first.getInstructionsExecuted());
        assertEquals(0, second.getInstructionsExecuted());
        assertEquals(List.of("Second", "First"), engine.release("Holder", "B", 1).unblockedProcessIds());
        int finished = engine.getViewedSeq();

        engine.jumpTo(blocked);
        assertEquals(ProcessState.WAITING, second.getState());
        assertEquals(0, second.getNextOperationIndex());
        assertEquals(0, second.getInstructionsExecuted());
        assertAccounting(scenario);
        engine.jumpTo(finished);
        for (Process completed : List.of(first, second)) {
            assertEquals(ProcessState.COMPLETED, completed.getState());
            assertEquals(1, completed.getNextOperationIndex());
            assertEquals(1, completed.getInstructionsExecuted());
        }
        assertTrue(engine.step().finished());
        assertAccounting(scenario);
    }

    @Test
    void holdAndWaitGrantsARequestGroupOnlyWhenAllResourcesAreAvailable() {
        Scenario scenario = new Scenario("Atomic requests");
        Process waiter = process(scenario, "Waiter");
        process(scenario, "Holder");
        scenario.addResource(new Resource("A", 1, false));
        scenario.addResource(new Resource("B", 1, false));
        op(scenario, waiter, REQUEST, "A");
        op(scenario, waiter, REQUEST, "B");
        op(scenario, waiter, RELEASE, "A");
        op(scenario, waiter, RELEASE, "B");
        var prevention = scenario.getPreventionToggles();
        prevention.setHoldAndWait(true);
        scenario.setPreventionToggles(prevention);
        SimulationEngine engine = load(scenario);

        engine.request("Holder", "B", 1);
        assertFalse(engine.step().finished());
        assertEquals(ProcessState.WAITING, waiter.getState());
        assertEquals(Map.of("A", 1, "B", 1), waiter.getPendingRequest().demand());
        assertEquals(0, waiter.getNextOperationIndex());
        assertTrue(waiter.getHeld().isEmpty());
        assertEquals(1, scenario.resource("A").getAvailable());

        engine.release("Holder", "B", 1);
        assertEquals(Map.of("A", 1, "B", 1), waiter.getHeld());
        assertEquals(2, waiter.getNextOperationIndex());
        assertEquals(2, waiter.getInstructionsExecuted());
        assertFalse(engine.step().finished());
        assertTrue(engine.step().finished());
        assertAccounting(scenario);
    }

    @Test
    void prohibitedSecondAcquisitionPhaseStopsScriptAndReleasesItsHoldings() {
        Scenario scenario = new Scenario("Invalid acquisition phase");
        Process process = process(scenario, "P");
        scenario.addResource(new Resource("A", 1, false));
        scenario.addResource(new Resource("B", 1, false));
        op(scenario, process, REQUEST, "A");
        op(scenario, process, REQUEST, "B");
        op(scenario, process, RELEASE, "B");
        op(scenario, process, REQUEST, "B");
        var prevention = scenario.getPreventionToggles();
        prevention.setHoldAndWait(true);
        scenario.setPreventionToggles(prevention);
        SimulationEngine engine = load(scenario);

        engine.step();
        engine.step();
        StepResult rejected = engine.step();
        assertTrue(rejected.finished());
        assertTrue(rejected.description().contains("Hold and wait prevention"));
        assertEquals(ProcessState.TERMINATED, process.getState());
        assertEquals(3, process.getNextOperationIndex());
        assertEquals(3, process.getInstructionsExecuted());
        assertEquals("Scenario finished", engine.step().description());
        assertAccounting(scenario);
    }

    @Test
    void automaticPreemptionReturnsHoldingsAndReacquiresThemWithThePendingRequest() {
        Scenario scenario = new Scenario("Release when blocked");
        Process first = process(scenario, "First");
        process(scenario, "Second");
        scenario.addResource(new Resource("A", 1, false));
        scenario.addResource(new Resource("B", 1, false));
        op(scenario, first, REQUEST, "B");
        op(scenario, first, RELEASE, "A");
        var prevention = scenario.getPreventionToggles();
        prevention.setNoPreemption(true);
        scenario.setPreventionToggles(prevention);
        SimulationEngine engine = load(scenario);

        engine.request("First", "A", 1);
        engine.request("Second", "B", 1);
        assertFalse(engine.step().finished());
        assertEquals(ProcessState.WAITING, first.getState());
        assertEquals(Map.of("A", 1, "B", 1), first.getPendingRequest().demand());
        assertTrue(first.getHeld().isEmpty());
        assertEquals(0, first.getInstructionsExecuted());
        assertEquals(1, scenario.resource("A").getAvailable());

        assertEquals(RequestOutcome.GRANTED, engine.request("Second", "A", 1).outcome());
        assertFalse(engine.detect().result().isDeadlocked());
        assertTrue(engine.release("Second", "B", 1).unblockedProcessIds().isEmpty());
        assertEquals(List.of("First"), engine.release("Second", "A", 1).unblockedProcessIds());
        assertEquals(Map.of("A", 1, "B", 1), first.getHeld());
        assertEquals(1, first.getInstructionsExecuted());
        assertTrue(engine.step().finished());
        assertAccounting(scenario);
    }

    @Test
    void circularWaitChecksConfiguredOrderAcrossSharedAndExclusiveResources() {
        Scenario scenario = new Scenario("Ordered requests");
        Process process = process(scenario, "P");
        scenario.addResource(new Resource("Z", 1, true));
        scenario.addResource(new Resource("A", 2, false));
        op(scenario, process, REQUEST, "Z");
        op(scenario, process, REQUEST, "A");
        op(scenario, process, REQUEST, "A");
        var prevention = scenario.getPreventionToggles();
        prevention.setCircularWait(true);
        prevention.setMutualExclusion(true);
        scenario.setPreventionToggles(prevention);
        SimulationEngine engine = load(scenario);

        assertFalse(engine.step().finished());
        assertFalse(engine.step().finished());
        assertEquals(Map.of("Z", 1, "A", 1), process.getHeld());
        assertEquals(1, scenario.resource("A").getAvailable());
        assertTrue(engine.step().description().contains("Circular wait prevention"));
        assertEquals(ProcessState.TERMINATED, process.getState());
        assertEquals(2, process.getInstructionsExecuted());
        assertAccounting(scenario);
    }

    @Test
    void declaredMaximumUsesPeakOutstandingUnitsAcrossReleases() {
        Scenario scenario = new Scenario("Peak claim");
        Process process = process(scenario, "P");
        scenario.addResource(new Resource("A", 1, false));
        op(scenario, process, REQUEST, "A");
        op(scenario, process, RELEASE, "A");
        op(scenario, process, REQUEST, "A");
        scenario.setBankersAvoidance(true);
        SimulationEngine engine = load(scenario);

        assertEquals(Map.of("A", 1), scenario.maximumClaims("P"));
        assertFalse(engine.step().finished());
        assertFalse(engine.step().finished());
        assertTrue(engine.step().finished());
        assertAccounting(scenario);
    }

    @Test
    void aMultiInstanceSelfDeadlockCanBeRecovered() {
        Scenario scenario = new Scenario("Self deadlock");
        Process process = process(scenario, "P");
        scenario.addResource(new Resource("A", 2, false));
        SimulationEngine engine = load(scenario);

        assertEquals(RequestOutcome.GRANTED, engine.request("P", "A", 2).outcome());
        assertEquals(RequestOutcome.BLOCKED, engine.request("P", "A", 1).outcome());
        assertEquals(List.of("P"), engine.detect().result().getProcessIds());
        assertTrue(engine.recover(new PriorityPolicy(), true).resolved());
        assertEquals(ProcessState.RUNNING, process.getState());
        assertFalse(process.isWaiting());
        assertAccounting(scenario);
    }

    @Test
    void shareableTypesRemainExclusiveWithTheSharingFlagOff() {
        Scenario scenario = new Scenario("Sharing off");
        process(scenario, "P1");
        process(scenario, "P2");
        scenario.addResource(new Resource("A", 1, true));
        SimulationEngine engine = load(scenario);

        assertEquals(RequestOutcome.GRANTED, engine.request("P1", "A", 1).outcome());
        assertEquals(RequestOutcome.BLOCKED, engine.request("P2", "A", 1).outcome());
        assertEquals(0, scenario.resource("A").getAvailable());
        assertEquals(List.of("P2"), engine.release("P1", "A", 1).unblockedProcessIds());
        assertAccounting(scenario);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void multiInstanceRecoverySkipsZeroHolderAndUnproductiveCandidatesAndResolvesTwoCycles(boolean preempt) {
        Scenario scenario = new Scenario("Two multi-instance deadlocks");
        Process downstream = new Process("Downstream", -2);
        Process noHoldings = new Process("NoHoldings", -1);
        scenario.addProcess(downstream);
        scenario.addProcess(noHoldings);
        scenario.addProcess(new Process("EndOfChain", -3));
        Process first = process(scenario, "P1");
        process(scenario, "P2");
        process(scenario, "P3");
        process(scenario, "P4");
        scenario.addResource(new Resource("0Shared", 1, true));
        for (String id : List.of("A", "B", "C", "D", "Unused", "Unrelated")) {
            scenario.addResource(new Resource(id, 2, false));
        }
        var prevention = scenario.getPreventionToggles();
        prevention.setMutualExclusion(true);
        scenario.setPreventionToggles(prevention);
        SimulationEngine engine = load(scenario);

        engine.request("Downstream", "Unused", 2);
        engine.request("EndOfChain", "Unrelated", 1);
        engine.request("P1", "0Shared", 1);
        engine.request("P2", "0Shared", 1);
        engine.request("P1", "A", 2);
        engine.request("P2", "B", 2);
        engine.request("P3", "C", 2);
        engine.request("P4", "D", 2);
        for (String id : List.of("P1", "P2", "P3", "P4", "NoHoldings", "Downstream", "EndOfChain")) {
            String demand = switch (id) {
                case "P1" -> "B";
                case "P3" -> "D";
                case "P4" -> "C";
                case "EndOfChain" -> "Unused";
                default -> "A";
            };
            assertEquals(RequestOutcome.BLOCKED, engine.request(id, demand, 1).outcome());
        }
        assertAccounting(scenario);

        DetectReport detected = engine.detect();
        assertEquals("BANKERS", detected.strategy());
        assertEquals(List.of("Downstream", "EndOfChain", "P1", "P2", "P3", "P4"), detected.result().getProcessIds());
        assertFalse(noHoldings.isDeadlocked());

        RecoveryReport recovered = engine.recover(new PriorityPolicy(), preempt);
        assertTrue(recovered.resolved());
        assertEquals(List.of("P1", "P3"), recovered.rounds().stream().map(RecoveryRound::victimId).toList());
        assertFalse(recovered.rounds().get(0).resolvedAfter());
        assertTrue(recovered.rounds().get(1).resolvedAfter());
        assertEquals(preempt ? ProcessState.RUNNING : ProcessState.TERMINATED, first.getState());
        if (preempt) {
            assertTrue(engine.getHistoryEvents().stream().anyMatch(e -> e.getType() == EventType.PREEMPT
                    && e.getDescription().contains("resource A from victim P1")));
        }
        assertAccounting(scenario);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryCostUsesRealScriptedWorkAndRestoresItFromSnapshots(boolean preempt) {
        Scenario scenario = new Scenario("Runtime recovery cost");
        Process first = process(scenario, "P1");
        Process second = process(scenario, "P2");
        process(scenario, "Gatekeeper");
        for (String id : List.of("A", "B", "C", "Gate")) scenario.addResource(new Resource(id, 1, false));
        op(scenario, first, REQUEST, "C");
        op(scenario, first, RELEASE, "C");
        op(scenario, first, REQUEST, "A");
        op(scenario, first, REQUEST, "B");
        op(scenario, second, REQUEST, "B");
        op(scenario, second, REQUEST, "A");
        SimulationEngine engine = load(scenario);

        engine.request("Gatekeeper", "Gate", 1);
        engine.request("P1", "Gate", 1);
        engine.step();
        engine.release("Gatekeeper", "Gate", 1);
        engine.release("P1", "Gate", 1);
        for (int i = 0; i < 5; i++) assertFalse(engine.step().finished());
        int deadlocked = engine.getViewedSeq();

        assertEquals(3, first.getInstructionsExecuted());
        assertEquals(1, second.getInstructionsExecuted());
        assertEquals(first.totalHeld(), second.totalHeld());
        assertEquals(List.of("P1", "P2"), engine.detect().result().getProcessIds());
        engine.jumpTo(0);
        assertEquals(0, first.getInstructionsExecuted());
        assertEquals(0, second.getInstructionsExecuted());
        engine.jumpTo(deadlocked);

        RecoveryReport recovered = engine.recover(new RecoveryCostPolicy(), preempt);
        assertTrue(recovered.resolved());
        assertEquals("P2", recovered.rounds().get(0).victimId());
        assertEquals(ProcessState.COMPLETED, first.getState());
        assertEquals(4, first.getInstructionsExecuted());
        assertEquals(preempt ? 0 : 1, second.getInstructionsExecuted());
        assertEquals(preempt ? 0 : 1, second.getNextOperationIndex());
        if (!preempt) {
            assertTrue(engine.step().finished());
            var processes = JsonMapper.mapState(engine).getAsJsonArray("processes");
            assertEquals(0, processes.get(1).getAsJsonObject().get("pendingOperations").getAsInt());
        }
        assertAccounting(scenario);
    }

    private static SimulationEngine load(Scenario scenario) {
        SimulationEngine engine = new SimulationEngine();
        engine.configure(scenario);
        return engine;
    }

    private static Process process(Scenario scenario, String id) {
        Process process = new Process(id, 1);
        scenario.addProcess(process);
        return process;
    }

    private static void op(Scenario scenario, Process process, OperationType action, String resource) {
        scenario.addOperation(new Operation(process.getId(), action, resource, 1));
    }

    private static void assertAccounting(Scenario scenario) {
        for (Resource resource : scenario.getResources()) {
            long allocations = 0;
            for (Process process : scenario.getProcesses()) {
                int held = process.getHeld().getOrDefault(resource.getId(), 0);
                assertTrue(held >= 0 && held <= resource.getTotal());
                if (process.isTerminal()) assertEquals(0, held);
                allocations += held;
            }
            assertTrue(resource.getAvailable() >= 0);
            assertEquals(resource.getTotal(), resource.getAvailable() + (scenario.isShared(resource) ? 0 : allocations));
        }
    }
}
