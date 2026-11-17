## Object Collaboration and State

A class diagram tells you what the system contains. To understand why those classes exist, follow what happens when the user clicks Request or Recover. These operations cross several object boundaries, and each boundary has a different responsibility.

### Configuring a scenario

Configuration begins at the browser form. The client submits process IDs, priorities, resources, instance counts, optional scripts and the chosen prevention and avoidance settings. `ApiServer` validates the request shape and uses `JsonMapper` to construct a scenario. The engine validates and installs it only if the configuration is acceptable.

A successful configuration starts a new trial: the resources are unallocated, processes start in `RUNNING`, script positions are at the beginning and history contains an initial state. An invalid configuration leaves the active scenario unchanged. This is an important ownership decision. The HTTP layer can reject malformed input, but the engine is responsible for deciding whether a valid model can replace the current trial.

### Resource request: who does what?

Start with a manual request from `P1` for one instance of `R2`.

1. The browser sends the request to `/api/request`. `ApiServer` resolves the current session, validates the input and dispatches to that session's `SimulationEngine`.
2. The engine checks whether `P1` is running, whether `R2` exists and whether the count is valid. It also applies the scenario's prevention rules.
3. If Banker's avoidance is enabled, the requested holdings must fit `P1`'s maximum scripted claim. A tentative grant is checked with `SafetyChecker` before it is committed.
4. A valid grant updates the relevant holdings and exclusive available capacity. A request that cannot proceed becomes pending, leaving the process in `WAITING`. A prevention violation rejects the request instead.
5. The engine records the committed effects in history. The API returns the full current state and the request outcome; the browser redraws the graph and panels from that response.

The result distinguishes four outcomes: `GRANTED`, `BLOCKED`, `DENIED_UNSAFE` and `REJECTED_PREVENTION`. A blocked request lacks available capacity. An unsafe denial means the proposed allocation passed capacity checking but failed the optional safety check. A prevention rejection means the enabled rule did not permit the operation. Keeping these distinct helps the interface explain what happened without duplicating the decision logic.

At the Java boundary, a named result makes these cases explicit. The following is a shortened example of how a caller can interpret the engine's outcome; it is a usage sketch, not a copy of the actual API handler.

```java
switch (outcome) {
    case GRANTED -> showGranted();
    case BLOCKED -> showCapacityWait();
    case DENIED_UNSAFE -> showUnsafeAllocation();
    case REJECTED_PREVENTION -> showPreventionRule();
}
```

The handler's real job is to serialize the engine's result, not to implement the grant rules again. The methods above stand for presentation responses; they are not additional classes or API operations in the repository.

A release takes the same route through the engine but has another consequence: returned exclusive instances may make waiting requests grantable. The engine reconsiders those requests in configured process order. A delayed grant of a scripted request advances the original script; a delayed manual grant leaves its script cursor alone. Pending-request provenance therefore belongs in the model, not in a frontend event handler.

### Scripted execution

`Step` does not advance every process simultaneously. It chooses the first `RUNNING` process with remaining scripted work in configured process order and attempts that process's next operation.

A scripted request that blocks keeps its operation position; another runnable process can proceed on a later step. When Hold and Wait prevention is enabled, consecutive scripted requests form an atomic group: either the group can be acquired or no partial allocation is made. The group advances its scripted positions only when it is actually granted.

When a process successfully completes its final scripted operation, it enters `COMPLETED` and releases any resources still held. That release can wake another process. A process with no scripted work can still accept manual requests. This is why the model tracks script progress and lifecycle state separately.

### Deadlock detection

Detection begins with a stable view of the current scenario. The engine chooses `CycleDetection` when every resource type has one instance. If any resource type has multiple instances, it chooses `BankersDetection`, which reasons about outstanding requests, available exclusive units and what finishable processes could return.

The choice matters because a cycle is conclusive only for the single-instance case. With several units available for a resource type, a process in a structural cycle might still complete and release enough resources to let the others proceed. The detector returns a `DetectionResult`; the engine and API use that result to mark confirmed deadlocked processes without treating every cycle in the graph as proof.

Detection records a history event even when no deadlock exists. It is an observation of a particular simulator state, so moving through the history should let the user recover the state being inspected.

### Recovery: choosing and applying an intervention

Recovery has three separate decisions: which processes are actually deadlocked, which of them can usefully be selected as victims and what to do to the chosen victim.

1. `SimulationEngine` runs detection, whether or not Detect was previously clicked.
2. The engine follows blocking dependencies to identify eligible candidates whose exclusive holdings contribute to the deadlock.
3. A `VictimPolicy` selects one candidate using Priority, Resources Held or Recovery Cost. Equal scores are resolved by process ID.
4. A `RecoveryStrategy` applies termination or preemption to the scenario.
5. The engine reconsiders pending requests, detects again and starts another round if a deadlock remains and another useful victim can be selected.
6. Committed rounds create history events describing victim selection, intervention and outcome.

Termination sets the victim to `TERMINATED`, clears its pending request and releases all holdings. Preemption releases the victim's holdings and restarts it in `RUNNING` at the start of its script, resetting its executed-instruction count. This is a restart within the simulator, not a full operating-system checkpoint restore.

The default victim policy is Recovery Cost, defined as held resource units plus successfully executed scripted instructions. Priority selects the lowest numeric priority; Resources Held selects the fewest held units. Manual operations and unsuccessful attempts do not increase the scripted-instruction count.

Recovery can take more than one round. If a round succeeds but later rounds cannot finish the job, the completed changes stay committed and the result is unresolved. If no intervention can begin, the state and history are preserved. This behavior would be harder to express if the detector, victim selector and recovery action were one large method with no distinct responsibilities.

### Process lifecycle

The simulator has four process states:

| State | Meaning | Typical next transition |
|---|---|---|
| `RUNNING` | The process can execute a manual or scripted operation. | To `WAITING` on a blocked request; to `COMPLETED` after its final successful scripted operation. |
| `WAITING` | The process has a pending resource demand. | Back to `RUNNING` when its demand is granted, or to `COMPLETED` if that grant finishes its script. |
| `COMPLETED` | All scripted work has completed and remaining holdings were released. | Terminal for that trial. |
| `TERMINATED` | Recovery or a scripted prevention violation stopped the process. | Terminal for that trial. |

A `deadlocked` flag is not a fifth state. A waiting process can be blocked without being in a deadlock. Detection changes the flag when it confirms a deadlock; the process remains `WAITING`. Preemption can restart a victim in `RUNNING` with its script progress reset.

There is no `READY` state because the simulator does not schedule CPU time. A process becomes runnable when it is no longer waiting for resources.

### Event history and snapshots

The history is more than a textual activity log. Each selectable `HistoryEvent` references a captured `Snapshot` of runtime state. That snapshot includes process states, deadlock flags, holdings, available resource counts, pending requests, script cursors and executed-instruction counts. It also preserves the information needed to distinguish scripted requests from manual ones.

The scenario configuration and its scripts are not copied into a new independent scenario for every event. They remain on the active scenario. Restoring a snapshot updates the runtime fields on that same configuration, after checking the expected process and resource identities.

Several events can point to one snapshot. A release followed by newly granted requests and process completions may produce multiple descriptions of a single committed transition, all of which restore the state after those effects. This is why `HistoryEvent` to `Snapshot` is not necessarily a one-to-one relationship.

### Revisiting the past and creating a branch

Selecting an old event restores its snapshot immediately. It does not replay all earlier events or rerun the deadlock algorithms. Step Back, Step Forward and direct selection all rely on the same restoration model, so the graph, process panel, resource counts and operation progress agree about which moment is being viewed.

A new command that commits events while an older snapshot is selected replaces the later history with a new branch. Simply navigating does not delete the future. A rejected operation that commits nothing also preserves that future. A successful Detect command is recorded and can create a branch even if it finds no deadlock.

For commands with several effects, the engine uses staging so an exception can restore the pre-command state and discard uncommitted events. Recovery rounds that have been committed are treated differently: partial recovery progress remains visible if a later round cannot resolve the deadlock.

The [domain model](domain-model.md) describes the ownership relationships behind these objects. [Design patterns](design-patterns.md) looks more closely at the interfaces that keep detection, victim selection and recovery separate.
