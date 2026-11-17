## Design Patterns

Deadlock Playground has three decisions that change independently: how to detect a deadlock, which process to select when recovery is necessary, and what intervention to apply. Those decisions are different from the rest of the simulation's job. A request still has to update holdings correctly, a completed process still has to release resources, and history still has to record the resulting state regardless of which policy was selected.

The project uses **Strategy** for those interchangeable decisions and **Facade** for the public operations of the simulation engine. Both solve problems that exist in this application. The design does not need a pattern for every class or every `new` expression.

### Strategy: three places where behavior varies

A Strategy interface defines a common operation while concrete implementations supply different behavior. The caller can work against that interface instead of embedding every algorithm in one growing conditional block.

#### Deadlock detection

A cycle is sufficient to establish deadlock when every resource type has a single instance. Once a resource type has multiple instances, a graph cycle by itself is no longer proof: available instances and the resources that finishable processes can return matter. The simulator therefore has two detectors behind `DetectionStrategy`:

| Implementation | When it is used | What it examines |
|---|---|---|
| `CycleDetection` | All resource types have one instance | Directed cycles in the Resource Allocation Graph |
| `BankersDetection` | At least one resource type has multiple instances | Outstanding requests, current allocations and exclusive availability |

This is the shape of the Java collaboration. The snippet is deliberately shortened to show the Strategy boundary; refer to the linked classes for the exact constructors and method bodies.

```java
// Conceptual excerpt: interface and dispatch, with setup omitted.
DetectionStrategy detector = allResourcesSingleInstance
        ? new CycleDetection()
        : new BankersDetection();

DetectionResult result = detector.detect(graph);
```

The relevant code is in [`model/detection`](../backend/src/main/java/com/deadlocksim/model/detection/). `SimulationEngine` chooses the implementation for the current scenario. The detectors return a `DetectionResult`; they do not own the browser's highlighting or event history. Those are separate concerns.

`SafetyChecker` belongs beside this area of the model, but it answers a different question. Banker's *avoidance* asks whether a proposed grant would leave a safe state, based on declared maximum claims. Multi-instance *detection* works with actual outstanding requests and allocations. Treating those as one interchangeable strategy would obscure the difference.

#### Victim selection

After detection finds a deadlock, recovery must choose a process whose exclusive holdings contribute to the blocking dependency. That eligible set is narrower than every process marked deadlocked. `VictimPolicy` decides which eligible process to choose; it does not decide whether a deadlock exists.

The implementations reflect the three controls exposed by the simulator:

- `PriorityPolicy` selects the eligible process with the lowest numeric priority.
- `ResourcesHeldPolicy` selects the eligible process holding the fewest resource units.
- `RecoveryCostPolicy`, the default, scores held units plus successfully executed scripted instructions.

Ties are resolved by process ID. The distinction between selecting eligible candidates and ranking them is important. A policy that simply terminated any process with a low score could waste work without breaking the actual deadlock.

The code organization shows the relationship directly:

```java
// Simplified declarations showing the implemented type family.
interface VictimPolicy { /* victim selection contract */ }

final class PriorityPolicy implements VictimPolicy { /* ... */ }
final class ResourcesHeldPolicy implements VictimPolicy { /* ... */ }
final class RecoveryCostPolicy implements VictimPolicy { /* ... */ }
```

The concrete implementations live under [`model/victim`](../backend/src/main/java/com/deadlocksim/model/victim/). A caller supplies a `VictimPolicy` for a recovery command, and the engine uses the chosen policy when selecting each eligible victim.

#### Recovery intervention

Choosing a victim and deciding what to do with it are two different jobs. `RecoveryStrategy` covers the second one:

- `Terminate` moves the victim to `TERMINATED` and releases its holdings.
- `Preempt` releases its holdings and restarts the simulated process in `RUNNING` at the beginning of its operation sequence, resetting its executed-instruction count.

Both are exposed through the same recovery workflow, but their effects on the process lifecycle are different. The engine handles detection, victim selection, wake-ups and repeat rounds around the strategy, so those steps do not have to be duplicated in both implementations.

The implementation classes are under [`model/recovery`](../backend/src/main/java/com/deadlocksim/model/recovery/). Preemption here is a simulator restart, not a saved checkpoint inside a real operating system.

### Facade: one place to operate the simulation

Without a coordinating entry point, an HTTP handler would need to know how to validate a resource request, change resource counts, run safety checks, wake pending processes, update detection flags and record snapshots. Those responsibilities belong to the backend simulation, not to transport code.

`SimulationEngine` acts as a Facade for the application's use cases. It exposes operations for configuring a scenario, requesting and releasing resources, advancing scripted work, detecting and recovering from deadlock, resetting, and navigating history. Internally it coordinates model objects and policies.

A shortened example of the API boundary looks like this:

```java
// Illustrative call flow; actual argument types and result handling
// are defined by SimulationEngine and the HTTP adapter.
SimulationEngine engine = sessionEngine;

engine.configure(scenario);
engine.detect();
engine.reset();
```

This is a Facade, not a claim that the engine is small. The engine remains a stateful coordinator with substantial logic. Its public boundary is simpler than the set of objects and operations it coordinates, but the design still has to keep domain rules in the appropriate model and policy classes. A Facade that accumulated every algorithm would eventually turn into the very large class it was meant to simplify.

The browser does not invoke `SimulationEngine` directly. `ApiServer` receives an HTTP command, validates its transport-level input, resolves the current browser session and delegates to the matching engine. The Facade boundary exists **behind** the HTTP boundary.

### Why these patterns, and not more?

A Factory Method could make sense if the project supported genuinely different sources of scenario creation. It does not currently need one: scenarios are configured through the application, and adding a formal factory hierarchy would create classes without solving a separate construction problem. Creating a detector or recovery class in ordinary Java code is not, on its own, evidence of the Factory Method pattern.

An Observer pattern is also unnecessary for the current browser contract. Commands return an updated state; the frontend renders that response. The engine does not have to maintain a subscriber graph to keep views synchronized. Likewise, history snapshots make past states restorable, but that does not automatically make the implementation a Command pattern with general-purpose undo and redo.

The trade-off in Strategy is extra types and interface boundaries. They pay for themselves where behaviors genuinely vary. A fixed rule such as validating an instance count or identifying a process by ID does not need the same treatment.

### Where to look in the repository

The main implementation links are [`SimulationEngine`](../backend/src/main/java/com/deadlocksim/engine/SimulationEngine.java), [`DetectionStrategy`](../backend/src/main/java/com/deadlocksim/model/detection/DetectionStrategy.java), [`VictimPolicy`](../backend/src/main/java/com/deadlocksim/model/victim/VictimPolicy.java) and [`RecoveryStrategy`](../backend/src/main/java/com/deadlocksim/model/recovery/RecoveryStrategy.java).

For the sequence in which these objects actually collaborate, see [Object collaboration](object-collaboration.md). The [architecture](architecture.md) page explains where the engine sits relative to the HTTP and browser layers.