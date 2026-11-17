package com.deadlocksim.engine;

import com.deadlocksim.api.JsonMapper;
import com.deadlocksim.model.EventType;
import com.deadlocksim.model.Operation;
import com.deadlocksim.model.Process;
import com.deadlocksim.model.ProcessState;
import com.deadlocksim.model.Resource;
import com.deadlocksim.model.Scenario;
import com.deadlocksim.model.history.HistoryEvent;
import com.deadlocksim.model.victim.PriorityPolicy;
import com.deadlocksim.model.victim.VictimPolicy;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.deadlocksim.model.OperationType.RELEASE;
import static com.deadlocksim.model.OperationType.REQUEST;
import static org.junit.jupiter.api.Assertions.*;

class HistoryConsistencyTest {
    @Test
    void scheduledGrantAndReleaseRoundTripAndCanBranchWithoutRepeatingAnInstruction() {
        Scenario scenario = scenario("P");
        scenario.setBankersAvoidance(true);
        scenario.addOperation(new Operation("P", REQUEST, "A", 1));
        scenario.addOperation(new Operation("P", RELEASE, "A", 1));
        scenario.addOperation(new Operation("P", REQUEST, "B", 1));
        SimulationEngine engine = configure(scenario);
        Process process = scenario.getProcesses().get(0);

        engine.step();
        assertLatestRoundTrip(engine);
        int grant = engine.getViewedSeq();
        int safety = grant - 1;
        assertSame(engine.history.get(grant).getSnapshot(), engine.history.get(safety).getSnapshot());

        engine.step();
        assertLatestRoundTrip(engine);
        int release = engine.getViewedSeq();
        assertEquals(EventType.RELEASE, engine.history.get(release).getType());
        assertEquals(2, process.getInstructionsExecuted());
        assertEquals(1, scenario.resource("A").getAvailable());

        assertTrue(engine.step().finished());
        assertLatestRoundTrip(engine);
        engine.jumpTo(safety);
        assertEquals(1, process.getNextOperationIndex());
        assertEquals(1, process.getInstructionsExecuted());
        assertEquals(0, scenario.resource("A").getAvailable());
        engine.step();
        assertEquals(safety + 1, engine.getViewedSeq());
        assertEquals(EventType.RELEASE, engine.history.get(engine.getViewedSeq()).getType());
        assertEquals(2, process.getNextOperationIndex());
        assertEquals(2, process.getInstructionsExecuted());
        assertEquals(1, scenario.resource("A").getAvailable());
        assertContiguousHistory(engine);
    }

    @Test
    void releaseRestoresAllWakesAndExecutionResumesWithCorrectProvenance() {
        Scenario scenario = scenario("Manual", "Script", "Holder");
        scenario.addResource(new Resource("Two", 2, false));
        scenario.addOperation(new Operation("Manual", REQUEST, "B", 1));
        scenario.addOperation(new Operation("Manual", RELEASE, "B", 1));
        scenario.addOperation(new Operation("Script", REQUEST, "Two", 1));
        scenario.addOperation(new Operation("Script", RELEASE, "Two", 1));
        SimulationEngine engine = configure(scenario);
        Process manual = scenario.getProcesses().get(0);
        Process scripted = scenario.getProcesses().get(1);

        engine.request("Holder", "Two", 2);
        engine.request("Manual", "Two", 1);
        int manualWait = engine.getViewedSeq();
        engine.step();
        int scriptedWait = engine.getViewedSeq();
        engine.jumpTo(manualWait);
        assertEquals(-1, manual.getPendingRequest().operationIndex());
        engine.jumpTo(scriptedWait);
        assertEquals(0, scripted.getPendingRequest().operationIndex());

        assertEquals(List.of("Manual", "Script"), engine.release("Holder", "Two", 2).unblockedProcessIds());
        assertLatestRoundTrip(engine);
        engine.jumpTo(scriptedWait + 1);
        assertEquals(EventType.RELEASE, engine.history.get(engine.getViewedSeq()).getType());
        assertEquals(ProcessState.RUNNING, manual.getState());
        assertEquals(ProcessState.RUNNING, scripted.getState());
        assertEquals(0, manual.getNextOperationIndex());
        assertEquals(1, scripted.getNextOperationIndex());
        assertEquals(0, manual.getInstructionsExecuted());
        assertEquals(1, scripted.getInstructionsExecuted());
        assertEquals(0, scenario.resource("Two").getAvailable());
        engine.step();
        assertEquals(1, manual.getHeld().get("B"));
        assertContiguousHistory(engine);
    }

    @Test
    void completionEventsRestoreTheDependentWake() {
        Scenario scenario = scenario("Waiter", "Finisher");
        scenario.addOperation(new Operation("Waiter", REQUEST, "A", 1));
        scenario.addOperation(new Operation("Finisher", REQUEST, "B", 1));
        SimulationEngine engine = configure(scenario);
        Process waiter = scenario.getProcesses().get(0);
        Process finisher = scenario.getProcesses().get(1);

        engine.request("Finisher", "A", 1);
        engine.step();
        int blocked = engine.getViewedSeq();
        assertTrue(engine.step().finished());
        assertLatestRoundTrip(engine);

        int last = engine.getViewedSeq();
        for (int seq = blocked + 1; seq <= last; seq++) {
            engine.jumpTo(seq);
            assertEquals(ProcessState.COMPLETED, waiter.getState());
            assertEquals(ProcessState.COMPLETED, finisher.getState());
            assertEquals(1, waiter.getInstructionsExecuted());
            assertEquals(1, scenario.resource("A").getAvailable());
            assertTrue(engine.step().finished());
            assertEquals(last + 1, engine.getHistoryEvents().size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allPostRecoveryEventsRestoreWakeAndResourceAccounting(boolean preempt) {
        Scenario scenario = scenario("Victim", "Waiter");
        scenario.addOperation(new Operation("Waiter", REQUEST, "A", 1));
        SimulationEngine engine = configure(scenario);
        Process victim = scenario.getProcesses().get(0);
        Process waiter = scenario.getProcesses().get(1);
        engine.request("Victim", "A", 1);
        engine.request("Waiter", "B", 1);
        engine.request("Victim", "B", 1);
        engine.step();

        assertTrue(engine.recover(eligible -> eligible.get(0), preempt).resolved());
        assertLatestRoundTrip(engine);
        var events = List.copyOf(engine.getHistoryEvents());
        int selected = events.stream().filter(event -> event.getType() == EventType.VICTIM_SELECTED)
                .findFirst().orElseThrow().getSequence();
        engine.jumpTo(selected);
        assertEquals(ProcessState.WAITING, waiter.getState());
        assertTrue(waiter.isDeadlocked());

        for (int seq = selected + 1; seq < events.size(); seq++) {
            engine.jumpTo(seq);
            assertEquals(ProcessState.COMPLETED, waiter.getState());
            assertEquals(1, waiter.getInstructionsExecuted());
            assertFalse(waiter.isDeadlocked());
            assertEquals(preempt ? ProcessState.RUNNING : ProcessState.TERMINATED, victim.getState());
            assertFalse(victim.isWaiting());
            assertEquals(1, scenario.resource("A").getAvailable());
            assertEquals(1, scenario.resource("B").getAvailable());
        }
    }

    @Test
    void invalidStepAndUnnecessaryRecoveryPreserveCurrentStateAndFutureEvents() {
        Scenario scenario = scenario("Script", "Holder", "Waiter");
        scenario.addOperation(new Operation("Script", RELEASE, "A", 1));
        SimulationEngine engine = configure(scenario);
        engine.request("Holder", "B", 1);
        engine.request("Waiter", "B", 1);
        int blocked = engine.getViewedSeq();
        engine.request("Script", "A", 1);
        engine.step();
        List<HistoryEvent> future = List.copyOf(engine.getHistoryEvents());
        engine.jumpTo(blocked);

        JsonObject before = JsonMapper.mapState(engine);
        long revision = engine.getRevision();
        assertEquals(SimulationException.Kind.NOT_HELD, assertThrows(SimulationException.class, engine::step).kind());
        assertEquals(SimulationException.Kind.NO_DEADLOCK,
                assertThrows(SimulationException.class, () -> engine.recover(new PriorityPolicy(), false)).kind());
        assertEquals(before, JsonMapper.mapState(engine));
        assertEquals(future, engine.getHistoryEvents());
        assertEquals(revision, engine.getRevision());

        assertFalse(engine.detect().result().isDeadlocked());
        assertEquals(blocked + 1, engine.getViewedSeq());
        assertEquals(EventType.DETECT, engine.history.get(engine.getViewedSeq()).getType());
        assertContiguousHistory(engine);
    }

    @Test
    void manualPreventionRejectionPreservesFutureHistory() {
        Scenario scenario = scenario("P");
        var toggles = scenario.getPreventionToggles();
        toggles.setCircularWait(true);
        scenario.setPreventionToggles(toggles);
        SimulationEngine engine = configure(scenario);
        engine.request("P", "B", 1);
        int granted = engine.getViewedSeq();
        engine.release("P", "B", 1);
        List<HistoryEvent> events = List.copyOf(engine.getHistoryEvents());
        engine.jumpTo(granted);

        JsonObject before = JsonMapper.mapState(engine);
        assertEquals(RequestOutcome.REJECTED_PREVENTION, engine.request("P", "A", 1).outcome());
        assertEquals(before, JsonMapper.mapState(engine));
        assertEquals(events, engine.getHistoryEvents());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryAfterOneRoundCommitsReportedProgressOrRollsBackOnException(boolean fail) {
        Scenario scenario = scenario("P1", "P2", "P3", "P4");
        scenario.addResource(new Resource("C", 1, false));
        scenario.addResource(new Resource("D", 1, false));
        SimulationEngine engine = configure(scenario);
        engine.request("P1", "A", 1);
        engine.request("P2", "B", 1);
        engine.request("P3", "C", 1);
        engine.request("P4", "D", 1);
        engine.request("P1", "B", 1);
        engine.request("P2", "A", 1);
        engine.request("P3", "D", 1);
        engine.request("P4", "C", 1);
        int deadlock = engine.getViewedSeq();
        engine.detect();
        List<HistoryEvent> events = List.copyOf(engine.getHistoryEvents());
        engine.jumpTo(deadlock);
        JsonObject before = JsonMapper.mapState(engine);
        long revision = engine.getRevision();

        RecoveryReport unavailable = engine.recover(eligible -> null, false);
        assertFalse(unavailable.resolved());
        assertTrue(unavailable.rounds().isEmpty());
        assertEquals(events, engine.getHistoryEvents());
        assertEquals(before, JsonMapper.mapState(engine));
        assertEquals(revision, engine.getRevision());

        AtomicInteger rounds = new AtomicInteger();
        VictimPolicy policy = eligible -> {
            if (rounds.getAndIncrement() == 0) return eligible.get(0);
            if (fail) throw new IllegalStateException("Failed policy");
            return null;
        };
        if (fail) {
            assertEquals("Failed policy", assertThrows(IllegalStateException.class, () -> engine.recover(policy, false)).getMessage());
            assertEquals(events, engine.getHistoryEvents());
            assertEquals(before, JsonMapper.mapState(engine));
            assertEquals(revision, engine.getRevision());
        } else {
            RecoveryReport recovery = engine.recover(policy, false);
            assertFalse(recovery.resolved());
            assertEquals(List.of("P1"), recovery.rounds().stream().map(RecoveryRound::victimId).toList());
            assertEquals(ProcessState.TERMINATED, scenario.getProcesses().get(0).getState());
            assertEquals("Recovery failed", engine.history.get(engine.getViewedSeq()).getDescription());
            assertLatestRoundTrip(engine);
            assertContiguousHistory(engine);
        }
    }

    private static Scenario scenario(String... processes) {
        Scenario scenario = new Scenario("History fixture");
        scenario.addResource(new Resource("A", 1, false));
        scenario.addResource(new Resource("B", 1, false));
        for (String process : processes) scenario.addProcess(new Process(process, 1));
        return scenario;
    }

    private static SimulationEngine configure(Scenario scenario) {
        SimulationEngine engine = new SimulationEngine();
        engine.configure(scenario);
        return engine;
    }

    private static void assertLatestRoundTrip(SimulationEngine engine) {
        JsonObject expected = JsonMapper.mapState(engine);
        int latest = engine.getViewedSeq();
        engine.jumpTo(0);
        engine.jumpTo(latest);
        assertEquals(expected, JsonMapper.mapState(engine));
    }

    private static void assertContiguousHistory(SimulationEngine engine) {
        assertEquals(IntStream.rangeClosed(0, engine.getViewedSeq()).boxed().toList(),
                engine.getHistoryEvents().stream().map(HistoryEvent::getSequence).toList());
    }
}
