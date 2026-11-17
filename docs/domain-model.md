## Domain Model

The domain model is the part of Deadlock Playground that knows what a process is, what a resource can be allocated to, and what it means for a request to wait. The browser draws the results, but the Java objects own the rules and state that make those results meaningful.

A useful way to read the model is to follow ownership first. A `Scenario` contains the configured processes, resources and operation scripts. A `SimulationEngine` keeps the active scenario and coordinates changes to it. Everything shown in the Resource Allocation Graph is derived from that same state. There is no second allocation model hidden inside the visualizer.

### Core objects and their responsibilities

| Type | Relevant state | Responsibility |
|---|---|---|
| `Scenario` | Process and resource collections, operation scripts, prevention settings | Hold a configured trial and coordinate consistent access to its entities. |
| `Process` | String ID, priority, lifecycle state, held counts, pending request, script cursor, executed-instruction count, deadlock flag | Track a process's runtime progress and resource demands. |
| `Resource` | String ID, total instance count, available count, shareable flag | Keep the accounting for one resource type. |
| `Operation` | Operation type, resource ID, count | Describe a scripted `REQUEST` or `RELEASE`. |
| `Graph` | Reference to a scenario | Derive process/resource nodes and request/allocation edges when needed. |
| `SafetyChecker` | No independent scenario state | Check whether a tentative grant leaves a safe sequence under declared maximum claims. |
| `History` | Ordered event list and selected position | Store committed events and support navigation or truncation. |
| `HistoryEvent` | Sequence, event type, description, snapshot reference | Describe one recorded change or observation. |
| `Snapshot` | Captured runtime data | Restore process and resource state without replaying operations. |
| `SimulationEngine` | Current scenario, committed history, private staged history | Coordinate use cases and keep state and history changes consistent. |

`ProcessState`, `OperationType` and `EventType` are enums. Result objects such as `RequestResult`, `SafetyResult` and `DetectionResult` carry outcomes back to callers. They don't need their own long-lived identity in the simulation.

The state vocabulary is deliberately small. The Java enum has four process states; the deadlock indicator is tracked separately so a waiting process is not automatically classified as deadlocked.

```java
// Condensed representation of the implemented ProcessState enum.
public enum ProcessState {
    RUNNING,
    WAITING,
    COMPLETED,
    TERMINATED
}
```

A similar `OperationType` enum distinguishes `REQUEST` from `RELEASE`. The operation's resource ID and count remain data on an `Operation`, instead of creating a subclass for each command.

One detail matters here: operation scripts belong to the scenario, while each process tracks its position in its script. A process does not own a separate copy of its whole program. This makes it possible to restore execution progress while keeping the configured script unchanged.

### Responsibilities and CRC analysis

CRC means Class, Responsibilities, Collaborators. It's a simple way of testing whether a class has a clear job and whether a use case can be completed by objects talking to one another instead of one class doing everything.

| Class or interface | Main responsibilities | Main collaborators |
|---|---|---|
| `Scenario` | Own configuration and coordinate resource accounting. | `Process`, `Resource`, `Operation`, `SimulationEngine` |
| `Process` | Track state, holdings, pending demand and script progress. | `Scenario`, `Resource` through resource IDs, recovery logic |
| `Resource` | Maintain capacity and available instances. | `Scenario`, processes holding the resource |
| `SimulationEngine` | Run operations, coordinate policies and commit history. | Scenario, checker, detector, victim policy, recovery strategy, history |
| `SafetyChecker` | Evaluate tentative allocation safety. | Process claims, resource counts, `SafetyResult` |
| `DetectionStrategy` | Determine deadlock from the current graph/state. | `Graph`, `DetectionResult` |
| `VictimPolicy` | Choose a process from eligible recovery candidates. | `Process`, detection result |
| `RecoveryStrategy` | Apply a selected intervention. | `Scenario`, victim process, resources |
| `History` | Keep ordered events and their saved states. | `HistoryEvent`, `Snapshot`, engine |
| `Graph` | Derive edges and nodes for the current model. | `Scenario`, detector, JSON mapper |

Consider the recovery use case. The engine asks a detection strategy for a result, uses that result to narrow the eligible victims, delegates selection to a victim policy and applies a recovery strategy to the scenario. The engine then rechecks blocked requests and detection before recording the outcome. The policy chooses; it doesn't terminate the process itself. The detector identifies the deadlock; it doesn't decide the recovery cost. Keeping those jobs separate makes the collaboration understandable.

### Class relationships

#### Scenario ownership

A configured `Scenario` has at least one process and one resource type. For a given scenario, each process and resource object belongs to that configuration. This is a strong whole-part relationship in the running model: replacing the scenario replaces its configured objects and scripted operations.

A `SimulationEngine` has zero or one active scenario. The `0..1` matters because Reset leaves the engine available while removing the scenario entirely. The engine also retains its history objects, which are cleared on Reset or successful reconfiguration.

#### Process and resource associations

Holding a resource is conceptually a many-to-many association. A process can hold several resource types, and a resource type may be held by several processes when it has multiple instances or is configured to allow sharing. The model records resource IDs and quantities rather than building a separate `Allocation` object for each edge.

A pending request is another relationship between a process and one or more resources, with requested counts. An allocation edge in the graph is derived from existing holdings; a request edge is derived from waiting demand. Neither edge is independently mutable simulation state.

Because resource quantities matter, a plain UML association line cannot carry the whole story. An association with a count attribute, or an explanation beside the class diagram, conveys the quantity without introducing a new runtime `Allocation` class that doesn't exist.

#### History, events and snapshots

`SimulationEngine` owns its committed `History` and a private staging history used while a command is being prepared. A `History` owns its ordered `HistoryEvent` entries. Events each reference exactly one `Snapshot`, while several events may reference the same snapshot when they belong to the same committed transition.

The history/event relationship is ownership, so composition is a better UML description than weak shared aggregation. The event/snapshot relationship is an association, not one-to-one composition: snapshots can be shared by several event entries, and a temporary checkpoint can exist outside the committed event list.

#### Strategy interfaces

`CycleDetection` and `BankersDetection` implement `DetectionStrategy`. `PriorityPolicy`, `ResourcesHeldPolicy` and `RecoveryCostPolicy` implement `VictimPolicy`. `Terminate` and `Preempt` implement `RecoveryStrategy`.

In UML these are *interface realizations*, drawn with a dashed line and a hollow triangle pointing to the interface. They're not superclass/subclass relationships between the concrete implementations. In Java, the useful polymorphism comes from implementing a common contract, not from inheriting shared fields from a base class.

### Encapsulation and information hiding

The most important invariant is that a resource cannot be granted twice as the same exclusive instance. Holdings recorded on a process and available counts recorded on a resource must agree. The engine and scenario coordinate those changes through model operations; the browser never directly edits Java fields.

There are two separate ideas here. **Encapsulation** groups a process's state with the operations that work on it. **Information hiding** keeps the representation of that state out of a caller's contract. The frontend needs to know that a process is waiting for `R2`, for example, but it doesn't need direct access to the collection used to store pending requests.

The same separation applies to history. A caller chooses an event to view; it doesn't edit arbitrary fields inside a snapshot. Restoration writes a captured runtime state back into the current scenario after checking the relevant process and resource IDs. The configured script remains on the scenario.

A result type is part of that boundary. Instead of exposing internal objects and asking the HTTP layer to inspect them for changes, the model reports a named outcome such as `GRANTED`, `BLOCKED`, `DENIED_UNSAFE` or `REJECTED_PREVENTION`.

### Abstraction, polymorphism and inheritance

A `DetectionStrategy` describes what detection must return without requiring its caller to know how it was calculated. For a scenario in which every resource type has one instance, cycle detection is sufficient. With multiple instances, the implementation uses a reduction based on current allocations, outstanding requests and available capacity. The calling use case still expects a `DetectionResult`.

The same idea applies to recovery policy selection. A victim policy receives eligible candidates and returns a selection. The algorithm inside the policy can change without changing the meaning of the recovery operation.

This is interface-based polymorphism. No inheritance tree of `Process` subclasses is needed: `RUNNING`, `WAITING`, `COMPLETED` and `TERMINATED` are states of the same type of object. Turning them into four subclasses would mix lifecycle with identity and make transitions awkward.

### What the graph knows

`Graph` retains a reference to the source `Scenario` and constructs its view of nodes and edges from the current state. Process and resource nodes have distinct kinds; their serialized IDs also use separate namespaces so a user-defined `P1` and `R1` cannot collide with each other or with edge IDs.

A visible cycle is a structural property of those edges. It becomes proof of deadlock only when each resource type has a single instance. With multiple instances, the detector must examine quantities. The `deadlocked` flag on a process is consequently separate from its `ProcessState`, and the visualization distinguishes structural cycle membership from confirmed detection.

The difference is deliberate. It prevents a view-layer convenience, such as coloring all nodes in a cycle red, from becoming a false claim about the system's state.

### Reading the implementation

The relevant Java code is in `backend/src/main/java/com/deadlocksim/`. Start with `model/Scenario.java`, `model/Process.java` and `model/Resource.java` for the entities; then read `engine/SimulationEngine.java` to see how their operations are coordinated. The `model/detection/`, `model/victim/` and `model/recovery/` packages contain the strategy implementations. The history types live under `model/history/`, with `Snapshot` in the model package.

The [analysis](analysis.md) document explains how these classes were identified. [Object collaboration](object-collaboration.md) follows them through the two most interesting runtime operations.
