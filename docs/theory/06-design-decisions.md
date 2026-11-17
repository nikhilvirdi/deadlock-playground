# 6. Design Decisions

## Avoidance

Banker's avoidance is opt-in when configuring a scenario. With avoidance off, requests may form real deadlocks for detection and recovery exercises.

With avoidance on, each process declares a fixed maximum per resource through its script. The maximum is the highest outstanding count in any prefix of the script. Requests add to the running count and releases subtract from it. A script cannot release more than it has acquired or declare a maximum above the resource capacity. A manual request must fit within the requesting process's declared maximum. A process with no declared demand for that resource must declare it in the script or use demo mode. Manual requests and releases are separate from the script and may require the user to reconcile the process's holdings before stepping further.

The safety check uses the tentative allocations, the available capacity after the proposed grant, and each unfinished process's remaining claim (maximum minus allocation). It looks for an order in which each process's remaining claim fits in the working capacity and its current holdings can then be returned. This gives a conservative guarantee against the declared maximum, including when a process's upcoming instructions would need less. An unsafe request waits without changing allocations and is reconsidered after resources return. Shared resources do not consume working capacity.

## Prevention Toggles

All four toggles are set when configuring a scenario.

- Mutual exclusion: shares only resource types marked shareable. Each process can hold up to that resource's configured instance count and concurrent holders do not reduce availability. Other resources continue to account for each granted instance.
- Hold and wait: each consecutive group of scripted requests starts with no holdings and is acquired atomically. A blocked group has no partial grants. A manual request is a group of one. A further group can start after all holdings are released.
- Release when blocked (the `noPreemption` API flag): enabling this breaks the Coffman no-preemption condition. On a capacity shortage, the requester returns its holdings and waits to reacquire them together with the new request. A demand that could never fit is rejected. With the flag off, a blocked requester keeps its holdings. An unsafe Banker denial leaves allocations intact.
- Circular wait: resources are ranked in scenario order. Requests must be above every held resource. The same order applies within an atomic request group and to shared resources.

A manual prevention violation leaves the process unchanged. A scripted prevention violation stops the script, reports the reason, and releases the process's holdings. The rejected operations do not advance the cursor.

## Detection and Recovery

Single-instance detection finds cycles. Multi-instance detection reduces by actual outstanding requests and allocations. Processes with no exclusive allocations can be waiting downstream but do not enter the reduction's deadlocked set. The remaining set may include holders downstream of a cycle. Recovery follows shortages after this reduction to select only holdings on a blocking cycle.

Both explicit recovery strategies are available independently of the prevention flags. Termination returns all holdings and preserves the victim's recorded progress. Preempt recovery selects a contended exclusive resource, then rolls back the victim to the beginning of its script and returns all its holdings. After every round, the engine wakes eligible waiters and detects again. Recovery reports failure if no eligible intervention remains.

There are three policies, and recovery cost is the default:

- Priority: select the lowest-priority eligible process.
- Resources held: select the eligible process holding the fewest units.
- Recovery cost: select the eligible process with the lowest sum of held units and executed instructions.

Ties go to the lower process ID. Every successful scripted request or release counts once, including a request granted later on wake-up. A granted group counts every instruction in that group. Manual actions, blocked attempts and rejected operations do not count. Rollback resets the counter, and a historical snapshot restores it.

## Execution History

- Event types: REQUEST, GRANT, BLOCK, RELEASE, STATE_CHANGE, AVOIDANCE_CHECK (safe or unsafe, with the sequence), DETECT, VICTIM_SELECTED, TERMINATE, PREEMPT, ROLLBACK, RESOLVED.
- Navigation: users can step backward and forward, or click any event to jump to that state.
- Storage: each selectable event points to a stable snapshot. The scenario configuration and its maximum claims are shared by all snapshots in that scenario. Snapshots preserve the execution cursor, instruction count, holdings, and the provenance of any pending request.
- Events from one transition can share a snapshot. A release, any grants it enables, and their completions all restore the state after those effects. A safety check and its successful grant share the resulting state. In recovery, victim selection restores the state before the intervention. The recovery events for a round restore the state after its wake-ups and redetection.
- A successful command commits its events together and discards any future after the selected snapshot. Jumping, a rejected manual prevention request, and a failed command with no committed effects preserve the event list. Detect is informational and records an event even when it finds no deadlock.
- A recovery that cannot continue after completing one or more rounds commits those rounds and reports an unresolved result. A command that throws restores its starting state and discards its staged events.

## Process States

- Running: able to proceed.
- Waiting: blocked on a request.
- Completed: the final scripted operation succeeded and all remaining holdings were returned, including when a delayed grant completes the script.
- Terminated: the process was stopped during recovery or after a scripted prevention violation. It has no holdings or runnable work, and its historical cursor is preserved.

A process with no script is available for manual operations.

Ready is not needed, because there is no CPU scheduler. "Deadlocked" is a flag on a process, not a state. Rollback returns a process to Running.

## Deferred

- In-app docs page list: decided at the end of the project.
