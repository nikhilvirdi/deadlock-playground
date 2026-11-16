package com.deadlocksim.model.victim;

import com.deadlocksim.model.Process;

import java.util.List;

public class RecoveryCostPolicy implements VictimPolicy {

    public static long costOf(Process p) {
        if (p == null) {
            throw new IllegalArgumentException("Process must not be null");
        }
        return (long) p.totalHeld() + p.getInstructionsExecuted();
    }

    @Override
    public Process selectVictim(List<Process> deadlocked) {
        if (deadlocked == null || deadlocked.isEmpty()) {
            throw new IllegalArgumentException("Deadlocked process list must not be null or empty");
        }

        Process victim = deadlocked.get(0);
        long lowestCost = costOf(victim);

        for (int i = 1; i < deadlocked.size(); i++) {
            Process current = deadlocked.get(i);
            long currentCost = costOf(current);

            if (currentCost < lowestCost) {
                victim = current;
                lowestCost = currentCost;
            } else if (currentCost == lowestCost) {
                if (current.getId().compareTo(victim.getId()) < 0) {
                    victim = current;
                    lowestCost = currentCost;
                }
            }
        }
        return victim;
    }
}
