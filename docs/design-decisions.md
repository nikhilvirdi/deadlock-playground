## Design Decisions and Trade-offs

The interesting part of Deadlock Playground is that the same resource allocation has to be understood in several ways. To the process, it is a held resource or pending request. To a detector, it is an edge or an entry in an allocation vector. To the UI, it is a line between two nodes. To the history system, it is state that must still make sense when the user jumps back five events.

These views need to agree, but that does not mean they should all own copies of the state. Much of the design follows from deciding who owns information, who is allowed to change it and where different algorithms should meet.

### One source of truth for resource accounting

`Scenario`, `Process` and `Resource` are the authority for the active trial. Process holdings, resource availability and pending requests are stored as runtime state. The Resource Allocation Graph is derived from that state rather than maintained as a second mutable allocation table.

That avoids a common consistency problem: a resource is released, the process panel updates, but an old allocation edge survives because a separate graph model was not updated. The cost is that graph nodes and edges need to be reconstructed when the state is requested. For a bounded teaching simulator, that is a reasonable trade-off.

The design also distinguishes resource **types** from resource **instances**. Each `Resource` tracks a total and available count. Individual units do not need separate identities or behavior in this model, so there is no class for each physical instance. A graph can still display an instance dot for each unit.

### Why a request is more than a boolean

A request can succeed, wait because capacity is unavailable, wait because Banker's avoidance rejects an unsafe tentative allocation, or fail a prevention rule. Collapsing those outcomes into `true` and `false` would lose information needed by both the simulation and the UI.

The Java model distinguishes the outcomes explicitly:

```java
// Abridged form of the RequestOutcome values used by the engine.
public enum RequestOutcome {
    GRANTED,
    BLOCKED,
    DENIED_UNSAFE,
    REJECTED_PREVENTION
}
```

The application also keeps a pending request's origin. A scripted operation that is granted later must advance the corresponding process's script cursor; a manual request granted later must not. Remembering that provenance in the domain is more reliable than making a button handler infer it from whichever control the user clicked last.

### Prevention, avoidance, detection and recovery are separate

These are related Operating Systems concepts, but they answer different questions:

| Mechanism | Decision it makes | When it runs |
|---|---|---|
| Prevention | Is a request disallowed by one of the configured Coffman-condition rules? | During request handling |
| Avoidance | Would a tentative grant leave a safe state under declared maximum claims? | During request handling, if enabled |
| Detection | Which processes are deadlocked in the current state? | On Detect and internally during recovery |
| Recovery | Which eligible victim should be changed, and by which intervention? | After detection confirms a deadlock |

Banker's avoidance is optional because always enforcing safety would make deliberate deadlock demonstrations difficult. When avoidance is off, requests can produce unsafe states as long as available capacity and any selected prevention settings permit them. Maximum claims in avoidance mode come from the highest outstanding counts in each process's scripted operations; they are not guessed from current holdings.

The simulator supports single-instance cycle detection and separate multi-instance reduction because a visible graph cycle has different implications in the two cases. Treating every structural cycle as a confirmed deadlock would give false results for some multi-instance configurations.

### Balancing coupling and cohesion

`Process` keeps its own runtime status, holdings and pending demand. `Resource` keeps its instance accounting. `Scenario` brings those objects into one trial. Detection and recovery behavior is moved into separate classes whose responsibilities differ from ordinary allocation changes.

`SimulationEngine` still knows about many of these objects. Some coupling is unavoidable in a coordinator whose job is to apply one command consistently across the model and history. Its Facade boundary keeps that coordination out of `ApiServer`, but it does not automatically prove the class has a single small responsibility.

The distinction is between *related coordination* and *unrelated decisions*. It is appropriate for the engine to call a detector and record the result. It would be harder to maintain if the HTTP layer implemented a second detector, or if every victim policy also updated browser-specific graph fields.

### Encapsulation and public protocols

The model keeps changing state behind operations instead of exposing resource counts as fields the browser can alter. The API accepts commands such as Request, Release and Recover; it does not accept a replacement map of arbitrary process holdings and trust it blindly.

A small example of this boundary can be written as:

```java
// Design sketch: state is read through methods and changed by domain rules.
private int availableInstances;

public int getAvailableInstances() {
    return availableInstances;
}

// Grant/release behavior checks capacity and keeps accounting consistent.
```

This sketch illustrates the intended access direction; it is not a verbatim copy of `Resource.java`. The code itself is the authority for individual accessor names and visibility. The important OOAD point is that an external client should not be able to turn a resource's availability negative merely by assigning a public field.

The public engine protocol is different again. It offers use cases, not low-level setters. The HTTP endpoint wraps that protocol, validates wire-format input and returns a full state representation.

### Inheritance, interfaces and the SOLID principles

The strongest examples of polymorphism here are interfaces for detection, victim selection and recovery. Each family has multiple implementations, and callers use the shared contract when executing a decision. In Java UML notation, those classes **realize** an interface. They do not inherit all their domain state from a common `Process` or `Resource` superclass.

The design demonstrates useful parts of SOLID without claiming full compliance by declaration:

- **Single Responsibility Principle:** resource accounting, detection, victim scoring and HTTP serialization are assigned to different classes. The engine remains a broad coordinator, so this is a design goal that still involves trade-offs.
- **Open/Closed Principle:** a new victim policy can implement `VictimPolicy` without changing the existing policy algorithms. The UI and policy-construction path may still need updating to expose it.
- **Liskov Substitution Principle:** strategy implementations should obey their shared contracts; callers should not need a special case because one implementation returns an incompatible kind of result.
- **Interface Segregation Principle:** the policy contracts are narrow; a detector is not forced to implement history navigation or request validation.
- **Dependency Inversion Principle:** the engine uses Strategy interfaces at important decisions, although some concrete implementations are constructed inside the engine. This is not complete constructor-injected inversion of every dependency.

These are observations about the actual structure, not a scorecard. The [Design patterns](design-patterns.md) page examines the interface families more closely.

### Snapshots instead of replaying commands

Time travel could have been implemented by starting a new scenario and replaying the event log up to the selected position. That would make navigation depend on rerunning logic, preserving every original command, and reproducing the exact order of later grants and callbacks. The project instead stores snapshots of the runtime fields needed to restore a recorded state.

Snapshots capture holdings, availability, process states, pending requests, deadlock flags, script progress and executed-instruction counts. The configured scenario and scripts remain shared. An event references the snapshot for the state it should display; multiple event descriptions can refer to the same snapshot if they describe one committed transition.

The cost is memory. History therefore has configured limits instead of pretending an unlimited number of copied states is free. The benefit is direct restoration without replay. If a user commits a new command while looking at an older event, the future events are truncated and a new branch begins. Navigation alone does not change that future.

### Transaction-like commands and failure handling

One user operation can trigger several model changes: releasing a resource may wake another process, complete its script and release further resources. Recording only the first change would leave the history misleading. The engine stages the events produced by a command and commits them together with the resulting state when the operation succeeds.

If an exception interrupts an attempt, the engine can restore its pre-command state and discard uncommitted events. Recovery has a deliberate exception to all-or-nothing *outcomes*: if a valid intervention round has already committed but later rounds cannot finish the deadlock, those completed rounds stay visible and the result is marked unresolved. This preserves real progress without claiming that recovery succeeded.

### Simplicity choices and deliberate limits

The frontend uses vanilla JavaScript, while the backend uses the JDK HTTP server and Gson. A larger frontend framework or server framework would make sense if the app had many screens, persistent user accounts or substantially more backend integration. Here it would add setup and abstractions to a relatively focused simulator.

Similarly, there is no database for sessions. Each browser has temporary in-memory state, which is straightforward for independent teaching trials but means inactivity expiration and server restarts clear the simulation. The application has bounded scenarios and histories rather than pretending resource usage cannot grow.

There are no textbook preset factories, general-purpose checkpoint systems or automatic OS process controls in the released simulator. Their absence is part of its scope, not an incomplete implementation of a design pattern.

### Reading the design critically

The current structure has useful separations, but a few dependencies are worth keeping visible. The engine creates some concrete algorithm and recovery classes, and the API chooses victim-policy implementations based on a request. Adding a new policy may therefore involve both a class and the relevant construction or UI path. The engine itself contains the orchestration for many state-changing operations, so testing history consistency across those commands matters as much as testing each detector in isolation.

The model deliberately favors correct, inspectable behavior over a large abstraction hierarchy. For the concrete ownership relationships, see [Domain model](domain-model.md). For the Java package boundaries and runtime session lifecycle, see [Architecture](architecture.md).
