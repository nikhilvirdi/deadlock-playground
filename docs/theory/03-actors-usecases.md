# 3. Actors and Use Cases

## Actors

- **User.** A student or instructor who configures and runs simulations, inspects Event History, and chooses how to recover from deadlock.

The browser frontend sends commands and reads results through HTTP endpoints. The backend validates commands, and the internal simulation engine manages resource allocations, process states, detection, recovery, and history. The frontend renders the returned state in the graph and panels. The backend isolates each browser session's simulation.

## UC1: Configure Scenario

- **Actor.** User
- **Precondition.** The application is open and the configuration form is available.
- **Main flow**
  1. The user enters a scenario name and adds processes with IDs and priorities.
  2. The user adds resource types with IDs, total instances, and shareability.
  3. The user can enter an ordered script of request and release operations for each process. Each operation specifies its process, action, resource, and count.
  4. The user chooses whether to enable Banker's avoidance and can enable any of the four prevention options. These are Mutual Exclusion, Hold and Wait, Release When Blocked, and Circular Wait.
  5. The user clicks Configure. The frontend validates the form, and the backend validates and loads the submitted scenario. A valid submission replaces any active scenario in the same session directly, clearing its prior execution progress and history.
- **Alternate flows**
  - **5a.** If the scenario is missing a process or resource type, has a blank name or ID, or repeats an ID within the process or resource list, configuration is rejected. The form reports the error, and any active scenario is preserved.
  - **5b.** The backend also rejects malformed values, including invalid priorities or flags, names or IDs over 128 characters or containing control characters, and resource totals outside 1 to 256. Script entries must refer to configured IDs, use a supported action and a positive integer count, and keep each request within the resource's total capacity. A scenario can contain up to 32 processes, 32 resource types, and 2,048 operations.
  - **5c.** With Banker's avoidance enabled, the script must define valid maximum claims. Its running allocation for each resource must stay between zero and that resource's total capacity.
- **Postcondition.** The scenario is active in the current session. Its processes are Running, its resources start unallocated, the graph and panels show the initial state, and Event History begins with the initial snapshot.

## UC2: Request Resource

- **Actor.** User
- **Precondition.** A scenario is loaded. The requested process must be Running.
- **Main flow**
  1. The user selects a process and resource in Actions, enters a count, and clicks Request.
  2. The engine validates the request and applies the enabled prevention rules. With Mutual Exclusion prevention enabled, sharing applies to resource types marked shareable.
  3. With Banker's avoidance enabled, the requested holdings must fit the process's declared maximum claim, which is derived from its script. If capacity is available, the safety check evaluates the tentative allocation and all active processes' remaining needs. Demo mode bypasses this safety check.
  4. When the result is `GRANTED`, the engine adds the units to the process's holdings. Only exclusive allocations reduce the available pool. The graph and panels reflect the grant, and history records it. An immediate grant in avoidance mode also records its safety check.
- **Alternate flows**
  - **2a.** `REJECTED_PREVENTION` means an enabled rule refused the request. Hold and Wait requires empty holdings before a new request group. Circular Wait permits requests only above the highest resource already held in the configured order. A manual prevention rejection explains the rule and preserves state and history.
  - **2b.** Invalid input, such as an unknown ID, a nonpositive count, an attempt by a process that is not Running, or a request exceeding a declared claim with avoidance enabled, produces a validation error and preserves state and history.
  - **3a.** `BLOCKED` means the required capacity is unavailable and the process waits on a pending request. If Release When Blocked applies, the engine returns the process's existing holdings and queues them together with the new demand. Waits update the graph and are recorded in history.
  - **3b.** `DENIED_UNSAFE` means the tentative grant fails Banker's safety check. The engine keeps allocations intact, puts the process in Waiting with the request pending, and records the safety result.
- **Postcondition.** A grant or wait is reflected in the updated state and history. A manual request, including one granted later, leaves the scripted operation position intact.

## UC3: Release Resource

- **Actor.** User
- **Precondition.** A scenario is loaded, and the selected process holds at least the requested release count for the selected resource.
- **Main flow**
  1. The user selects a process, resource, and count in Actions and clicks Release.
  2. The engine reduces the process's holdings. It returns exclusive units to the available pool and updates the allocation edge to reflect any units still held.
  3. The engine checks pending requests and grants those it can satisfy under the active settings. A process whose final scripted request is granted moves directly to Completed and returns its remaining holdings. Those releases can enable more pending requests.
  4. The frontend displays the updated graph and panels. History records the release and any resulting grants and completions.
- **Alternate flow**
  - **2a.** If the process holds fewer units than requested or the release input is invalid, the application reports an error and preserves the current state and history.
- **Postcondition.** The release and any resulting allocations are reflected in the resource pool. Granted waiters become Running or Completed.

## UC4: Step Through Simulation

- **Actor.** User
- **Precondition.** A scenario is loaded. The Step control is available when a Running process has pending scripted work.
- **Main flow**
  1. The user clicks Step in Actions.
  2. In configured process order, the engine selects the first Running process with a pending operation and attempts its next scripted request or release. With Hold and Wait enabled, it treats consecutive scripted requests as one group and grants them together. If Circular Wait is also enabled, it checks the resource order within the group.
  3. Successful scripted operations advance that process's position in the script. When its last operation succeeds, the process becomes Completed and releases its remaining holdings. The graph, panels, and history reflect the committed changes.
- **Alternate flows**
  - **2a.** When a scripted request or group is blocked or denied as unsafe, it stays pending and the process waits. Other eligible processes can run on subsequent steps. If the pending request is granted later, the engine advances exactly the operations in that request or group.
  - **2b.** A scripted prevention violation stops that process's script, marks it Terminated, and releases its holdings. A validation error, such as releasing more units than the process holds, preserves state and history.
  - **2c.** When no operation is runnable, Step is disabled in the frontend. A Step command sent directly to the backend reports that execution is finished if no pending work remains among nonterminal processes. If pending work is blocked, it reports that no process can run. Completed and Terminated processes are excluded from the pending-work check.
- **Postcondition.** Successful operations advance execution. Pending waits retain their script position until granted, and terminated scripts no longer count toward completion.

## UC5: Detect Deadlock

- **Actor.** User
- **Precondition.** A scenario is loaded.
- **Main flow**
  1. The user clicks Detect. The engine chooses cycle detection if every resource type has a single instance. For multi-instance scenarios it uses Banker's-style detection based on allocations, available instances, and outstanding requests.
  2. The frontend reports the confirmed deadlocked processes and displays one cycle when the single-instance detector returns it. Multi-instance detection reports the affected process IDs. Structural cycles can exist in a multi-instance graph even when detection finds that processes can proceed.
  3. The graph shows structural-cycle edges in gray and uses thicker edges for confirmed single-instance deadlock cycles. Confirmed deadlocked process nodes have a heavier outline and a `DEADLOCKED` label. The Processes panel marks the same processes.
  4. The detection result is recorded in Event History.
- **Alternate flow**
  - **2a.** If detection finds no deadlock, including when no process is waiting, the frontend reports “No deadlock detected.” The successful result clears deadlocked process flags and is recorded in Event History.
- **Postcondition.** The displayed detection result and process flags reflect the checked state.

## UC6: Recover from Deadlock

- **Actor.** User
- **Precondition.** A scenario is loaded.
- **Main flow**
  1. The user chooses Priority, Resources Held, or Recovery Cost and selects Terminate or Preempt. Recovery Cost is the default policy. The user clicks Recover.
  2. The engine runs deadlock detection internally and uses the selected policy to choose an eligible victim. Eligible victims hold exclusive units involved in a blocking cycle.
  3. Terminate stops the chosen process and releases its holdings. Preempt selects a contested resource and rolls the victim back to its initial Running state, returning its holdings and resetting its script position and executed-instruction count.
  4. The engine grants eligible pending requests, reruns detection, and repeats the intervention while a deadlock and an eligible victim remain.
  5. The frontend reports the rounds and whether recovery resolved the deadlock. Committed rounds record detection, victim selection, the selected intervention, and any resulting grants. Preemption records both preemption and rollback. When at least one round commits, the final `RESOLVED` entry records the overall outcome.
- **Alternate flows**
  - **2a.** If the initial check finds no deadlock, the backend returns `NO_DEADLOCK`. The frontend explains that recovery is unnecessary, and the attempt preserves state and history.
  - **4a.** If a deadlock remains and no eligible intervention remains, the system reports it as unresolved. Any completed rounds are committed. If no round could start, state and history are preserved.
  - **4b.** A command rejected by the backend, such as one with an invalid policy or strategy, reports an error. If the engine throws during an attempt, it restores the pre-command state and retains the original history.
- **Postcondition.** The graph, panels, and history reflect the committed recovery rounds. An unsuccessful attempt with no committed intervention leaves the original state available.

## UC7: Reset Scenario

- **Actor.** User
- **Precondition.** A scenario is loaded in the current browser session.
- **Main flow**
  1. The user clicks Reset. The frontend first sends the request without confirmation.
  2. If Event History contains only the initial event, the engine clears the active scenario immediately. A confirmed reset also clears any larger history.
  3. The frontend returns to the unconfigured view. The graph displays its empty-state message, the Processes and Resources panels are empty, and Event History has no entries.
- **Alternate flow**
  - **2a.** If the full history contains more than one event, even when an earlier event is selected, the backend requires confirmation. The frontend prompts the user and retries with confirmation if accepted. Cancelling preserves the scenario, execution progress, and history.
- **Postcondition.** The requesting session has no active scenario, allocations, waiting requests, execution progress, or recorded snapshots. Other sessions retain their own simulations.

## Event History Navigation

The user selects an event or uses Step Back and Step Forward to restore a recorded snapshot. The graph, Processes and Resources panels, script progress, and selected history event show the same restored state. Navigation preserves all recorded events. A valid action that commits a new event from an earlier snapshot replaces the later events with a new branch. Rejected commands that commit no events preserve the existing branch.

## Relationships

- The User is associated with UC1 through UC7.
- UC2 uses the safe-state check conditionally when Banker's avoidance is enabled.
- UC6 uses the detection logic described in UC5 before recovery and after each round. It runs detection internally when the user clicks Recover.
- Preempt is an alternative recovery strategy within UC6.
