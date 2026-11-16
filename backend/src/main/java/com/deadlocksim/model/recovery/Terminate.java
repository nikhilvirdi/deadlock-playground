package com.deadlocksim.model.recovery;

import com.deadlocksim.model.Process;
import com.deadlocksim.model.ProcessState;
import com.deadlocksim.model.Scenario;

public class Terminate implements RecoveryStrategy {

    @Override
    public void apply(Scenario s, Process victim) {
        if (s == null) {
            throw new IllegalArgumentException("Scenario must not be null");
        }
        if (victim == null) {
            throw new IllegalArgumentException("Victim must not be null");
        }
        if (!s.getProcesses().contains(victim)) {
            throw new IllegalArgumentException("Victim process is not in the scenario");
        }

        s.releaseAll(victim);

        victim.setState(ProcessState.TERMINATED);
        victim.setDeadlocked(false);
        victim.clearWaitingFor();
    }
}
