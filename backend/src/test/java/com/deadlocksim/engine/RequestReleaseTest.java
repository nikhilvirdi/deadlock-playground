package com.deadlocksim.engine;

import com.deadlocksim.model.EventType;
import com.deadlocksim.model.Operation;
import com.deadlocksim.model.OperationType;
import com.deadlocksim.model.PreventionToggles;
import com.deadlocksim.model.Process;
import com.deadlocksim.model.ProcessState;
import com.deadlocksim.model.Resource;
import com.deadlocksim.model.Scenario;
import com.deadlocksim.model.history.HistoryEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestReleaseTest {

    @Test
    void requestWithFreeInstancesAndSafeStateIsGrantedAndHoldingAppears() {
        SimulationEngine engine = new SimulationEngine();
        Scenario scenario = new Scenario("Test");
        Process p1 = new Process("P1", 1);
        Resource r1 = new Resource("R1", 3, false);
        scenario.addProcess(p1);
        scenario.addResource(r1);
        engine.configure(scenario);

        RequestResult result = engine.request("P1", "R1", 2);

        assertEquals(RequestOutcome.GRANTED, result.outcome());
        assertEquals(2, p1.totalHeld());
        assertEquals(2, p1.getHeld().get("R1"));
        assertEquals(1, r1.getAvailable());
        assertEquals(ProcessState.RUNNING, p1.getState());
        assertEquals(0, p1.getNextOperationIndex()); // manual request does not advance cursor
    }

    @Test
    void requestWithNoFreeInstanceIsBlockedProcessWaitingAndWaitingForSet() {
        SimulationEngine engine = new SimulationEngine();
        Scenario scenario = new Scenario("Test");
        Process p1 = new Process("P1", 1);
        Resource r1 = new Resource("R1", 1, false);
        scenario.addProcess(p1);
        scenario.addResource(r1);
        engine.configure(scenario);

        r1.grant(1);
        assertEquals(0, r1.getAvailable());

        RequestResult result = engine.request("P1", "R1", 1);

        assertEquals(RequestOutcome.BLOCKED, result.outcome());
        assertEquals(ProcessState.WAITING, p1.getState());
        assertTrue(p1.isWaiting());
        assertEquals("R1", p1.getWaitingResourceId());
        assertEquals(1, p1.getWaitingCount());
        assertEquals(0, p1.totalHeld());
    }

    @Test
    void unsafeGrantIsDeniedAgainstFutureClaimsAndRecorded() {
        SimulationEngine engine = new SimulationEngine();
        Scenario scenario = new Scenario("Avoidance Test");
        scenario.setBankersAvoidance(true);
        Process p1 = new Process("P1", 1);
        Process p2 = new Process("P2", 2);
        Resource r1 = new Resource("R1", 1, false);
        Resource r2 = new Resource("R2", 1, false);
        scenario.addProcess(p1);
        scenario.addProcess(p2);
        scenario.addResource(r1);
        scenario.addResource(r2);
        scenario.addOperation(new Operation("P1", OperationType.REQUEST, "R1", 1));
        scenario.addOperation(new Operation("P1", OperationType.REQUEST, "R2", 1));
        scenario.addOperation(new Operation("P2", OperationType.REQUEST, "R2", 1));
        scenario.addOperation(new Operation("P2", OperationType.REQUEST, "R1", 1));
        engine.configure(scenario);

        assertEquals(List.of("P1", "P2"), engine.request("P1", "R1", 1).safety().getSequence());
        assertFalse(p1.isWaiting());
        RequestResult result = engine.request("P2", "R2", 1);

        assertEquals(RequestOutcome.DENIED_UNSAFE, result.outcome());
        assertEquals(ProcessState.WAITING, p2.getState());
        assertEquals("R2", p2.getWaitingResourceId());
        assertEquals(1, r2.getAvailable());
        assertTrue(p2.getHeld().isEmpty());
        assertEquals(EventType.AVOIDANCE_CHECK, engine.getHistoryEvents().get(engine.getViewedSeq()).getType());
    }

    @Test
    void circularWaitOnRequestingLowerOrderedResourceWhileHoldingHigherIsRejectedPrevention() {
        SimulationEngine engine = new SimulationEngine();
        Scenario scenario = new Scenario("Circular Wait Test");
        Process p1 = new Process("P1", 1);
        Resource r1 = new Resource("R1", 3, false);
        Resource r2 = new Resource("R2", 3, false);
        scenario.addProcess(p1);
        scenario.addResource(r1);
        scenario.addResource(r2);

        PreventionToggles toggles = scenario.getPreventionToggles();
        toggles.setCircularWait(true);
        scenario.setPreventionToggles(toggles);

        engine.configure(scenario);

        // P1 requests and receives R2 (index 1)
        RequestResult res1 = engine.request("P1", "R2", 1);
        assertEquals(RequestOutcome.GRANTED, res1.outcome());

        // P1 requests R1 (index 0 < index 1)
        RequestResult res2 = engine.request("P1", "R1", 1);
        assertEquals(RequestOutcome.REJECTED_PREVENTION, res2.outcome());
        assertEquals(ProcessState.RUNNING, p1.getState());
        assertFalse(p1.isWaiting());
        assertEquals(1, p1.totalHeld());
        assertEquals(3, r1.getAvailable());
    }

    @Test
    void holdAndWaitOnRequestingWhileHoldingAnythingIsRejectedPrevention() {
        SimulationEngine engine = new SimulationEngine();
        Scenario scenario = new Scenario("Hold And Wait Test");
        Process p1 = new Process("P1", 1);
        Resource r1 = new Resource("R1", 3, false);
        Resource r2 = new Resource("R2", 3, false);
        scenario.addProcess(p1);
        scenario.addResource(r1);
        scenario.addResource(r2);

        PreventionToggles toggles = scenario.getPreventionToggles();
        toggles.setHoldAndWait(true);
        scenario.setPreventionToggles(toggles);

        engine.configure(scenario);

        // First request when holding nothing succeeds
        RequestResult res1 = engine.request("P1", "R1", 1);
        assertEquals(RequestOutcome.GRANTED, res1.outcome());

        // Second request while holding R1 fails
        RequestResult res2 = engine.request("P1", "R2", 1);
        assertEquals(RequestOutcome.REJECTED_PREVENTION, res2.outcome());
        assertEquals(ProcessState.RUNNING, p1.getState());
        assertFalse(p1.isWaiting());
        assertEquals(1, p1.totalHeld());
    }

    @Test
    void mutualExclusionOnSharesOnlyTheMarkedResource() {
        SimulationEngine engine = new SimulationEngine();
        Scenario scenario = new Scenario("Mutual Exclusion Test");
        Process p1 = new Process("P1", 1);
        Process p2 = new Process("P2", 2);
        Resource shared = new Resource("S", 1, true);
        Resource exclusive = new Resource("E", 1, false);
        scenario.addProcess(p1);
        scenario.addProcess(p2);
        scenario.addResource(shared);
        scenario.addResource(exclusive);
        PreventionToggles toggles = scenario.getPreventionToggles();
        toggles.setMutualExclusion(true);
        scenario.setPreventionToggles(toggles);
        engine.configure(scenario);

        assertEquals(RequestOutcome.GRANTED, engine.request("P1", "S", 1).outcome());
        assertEquals(RequestOutcome.GRANTED, engine.request("P2", "S", 1).outcome());
        assertEquals(1, shared.getAvailable());
        assertEquals(RequestOutcome.GRANTED, engine.request("P1", "E", 1).outcome());
        assertEquals(RequestOutcome.BLOCKED, engine.request("P2", "E", 1).outcome());
        assertEquals(0, exclusive.getAvailable());

        assertEquals(List.of("P2"), engine.release("P1", "E", 1).unblockedProcessIds());
        assertEquals(0, exclusive.getAvailable());
        assertEquals(1, p2.getHeld().get("E"));
        engine.release("P2", "S", 1);
        assertEquals(1, shared.getAvailable());
        assertEquals(1, p1.getHeld().get("S"));
    }

    @Test
    void releaseOfResourceNotHeldThrowsIllegalStateException() {
        SimulationEngine engine = new SimulationEngine();
        Scenario scenario = new Scenario("Test");
        Process p1 = new Process("P1", 1);
        Resource r1 = new Resource("R1", 2, false);
        scenario.addProcess(p1);
        scenario.addResource(r1);
        engine.configure(scenario);

        assertThrows(IllegalStateException.class, () -> engine.release("P1", "R1", 1));

        r1.grant(1);
        p1.holdInstances("R1", 1);

        assertThrows(IllegalStateException.class, () -> engine.release("P1", "R1", 2));
    }

    @Test
    void releaseWakesWaitingProcessWhichBecomesRunningAndHoldsResource() {
        SimulationEngine engine = new SimulationEngine();
        Scenario scenario = new Scenario("Wake Test");
        Process p1 = new Process("P1", 1);
        Process p2 = new Process("P2", 2);
        Resource r1 = new Resource("R1", 2, false);
        scenario.addProcess(p1);
        scenario.addProcess(p2);
        scenario.addResource(r1);
        engine.configure(scenario);

        // P1 holds 2 of R1 (all available)
        engine.request("P1", "R1", 2);
        assertEquals(0, r1.getAvailable());

        // P2 requests 1 of R1 and blocks
        RequestResult resBlock = engine.request("P2", "R1", 1);
        assertEquals(RequestOutcome.BLOCKED, resBlock.outcome());
        assertEquals(ProcessState.WAITING, p2.getState());
        assertTrue(p2.isWaiting());

        // P1 releases 1 of R1
        ReleaseResult rel = engine.release("P1", "R1", 1);

        assertEquals(List.of("P2"), rel.unblockedProcessIds());
        assertEquals(ProcessState.RUNNING, p2.getState());
        assertFalse(p2.isWaiting());
        assertNull(p2.getWaitingResourceId());
        assertEquals(0, p2.getWaitingCount());
        assertEquals(1, p2.totalHeld());
        assertEquals(0, p2.getNextOperationIndex()); // manual wait consumes no script
        assertEquals(0, r1.getAvailable()); // granted to P2
    }

    @Test
    void everyOperationAppendsEventsInOrder() {
        SimulationEngine engine = new SimulationEngine();
        Scenario scenario = new Scenario("History Test");
        Process p1 = new Process("P1", 1);
        Resource r1 = new Resource("R1", 2, false);
        scenario.addProcess(p1);
        scenario.addResource(r1);
        engine.configure(scenario);

        assertEquals(1, engine.getHistoryEvents().size());
        assertEquals(EventType.STATE_CHANGE, engine.getHistoryEvents().get(0).getType());

        engine.request("P1", "R1", 1);
        List<HistoryEvent> eventsAfterReq = engine.getHistoryEvents();
        assertEquals(2, eventsAfterReq.size());
        assertEquals(EventType.GRANT, eventsAfterReq.get(1).getType());

        engine.release("P1", "R1", 1);
        List<HistoryEvent> eventsAfterRel = engine.getHistoryEvents();
        assertEquals(3, eventsAfterRel.size());
        assertEquals(EventType.RELEASE, eventsAfterRel.get(2).getType());
    }
}

