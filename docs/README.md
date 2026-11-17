## Deadlock Playground: Design Documentation

Deadlock Playground is an interactive simulator for exploring resource allocation and operating-system deadlocks. You define processes, resources and request sequences, then watch the state change as resources are granted, processes wait, deadlocks are detected and recovery takes place.

The simulator is also an object-oriented design exercise. A process holding two resources is easy enough to describe in a diagram; deciding which object owns that information, who is allowed to change it, and how a recovery operation affects the rest of the system takes more thought. These notes follow those decisions from the original problem to the Java implementation.

These pages focus on the model that actually runs: its responsibilities, relationships, interfaces, state transitions and architectural boundaries. The Operating Systems concepts provide the problem; Object-Oriented Analysis and Design (OOAD) gives us the tools to structure the solution.

### Where to start

| Document | What you'll find |
|---|---|
| [Analysis](analysis.md) | The problem boundary, actors, use cases, noun analysis and the path from requirements to candidate classes. |
| [Domain model](domain-model.md) | The core objects, CRC-style responsibilities, class relationships, encapsulation and interface realization. |
| [Object collaboration](object-collaboration.md) | How objects work together during requests and recovery, plus process states, snapshots and history branching. |
| [Design patterns](design-patterns.md) | Why Strategy and Facade fit this simulator and where their limits are. |
| [Architecture](architecture.md) | The browser, HTTP adapter, simulation engine and domain layer, including runtime session boundaries. |
| [Design decisions](design-decisions.md) | Trade-offs behind the design, coupling and cohesion, relevant SOLID principles and alternatives we left out. |

The documents are related but can be read independently. If you're interested in the analysis process, start with [analysis](analysis.md) and [domain model](domain-model.md). If you're looking at the Java implementation, [architecture](architecture.md) and [object collaboration](object-collaboration.md) are more useful first.

### The system in one example

Take two processes, `P1` and `P2`, and two exclusive resources, `R1` and `R2`, each with one instance. Let `P1` hold `R1` while waiting for `R2`. Let `P2` hold `R2` while waiting for `R1`. Neither can proceed.

That situation connects most of the design:

- `Scenario` owns the configured processes, resources and operation sequences.
- `Process` tracks its state, holdings, script position and outstanding requests; `Resource` tracks capacity and availability.
- `SimulationEngine` handles a request or a step and coordinates the objects involved.
- `Graph` derives request and allocation edges from the same scenario state that the algorithms read.
- A `DetectionStrategy` identifies the deadlock. A `VictimPolicy` chooses an eligible process, and a `RecoveryStrategy` changes the state to break the dependency.
- `History` records events with `Snapshot` references, so an earlier state can be inspected without replaying every command.

The example is small, but the design also supports multiple instances per resource, optional Banker's avoidance, prevention settings, different victim policies and independent browser sessions.

### UML and implementation

The eight UML diagrams are embedded in the documents where they are discussed: the use case, activity and object views in [Analysis](analysis.md); the class diagram in [Domain model](domain-model.md); the request and recovery sequence diagrams and process state machine in [Object collaboration](object-collaboration.md); and the component/package diagram in [Architecture](architecture.md). Each inline preview is sized for GitHub and can be clicked to open the full SVG.

The backend packages are under `backend/src/main/java/com/deadlocksim/`. The main areas are `model` (domain objects and algorithms), `engine` (simulation coordination) and `api` (HTTP, JSON and session handling). The browser code is in `frontend/`. The public application is at [deadlock-playground.onrender.com](https://deadlock-playground.onrender.com/).

### Scope

This is a deterministic educational simulation, not an operating-system scheduler or a tool that inspects real processes. Its snapshots belong to one browser session and live in server memory. There is no account system, database, permanent save feature, automatic playback or general-purpose process checkpointing. The preemption recovery option restarts a simulated process from the beginning of its scripted work; history navigation restores previously recorded simulator states.

The underlying coursework notes helped shape the original design, but these pages describe the finished application's supported behavior. Where an early UML model and the implementation differ, the implementation is the reference.