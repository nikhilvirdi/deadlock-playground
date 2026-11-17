## Deadlock Playground

An interactive deadlock simulator. Configure processes and resources, watch them compete for allocations, and see what happens when neither side can move forward.

### About Deadlock Playground

**Deadlock Playground** is a mini project I built with [@takkritagya16](https://github.com/takkritagya16) for our **CSE 5th semester Object-Oriented Analysis and Design (OOAD) coursework**. It uses deadlock concepts we learned during our **4th semester Operating Systems coursework** and turns them into a browser-based simulation where you can create resource-allocation scenarios, run requests and releases, detect deadlocks, and try different ways of handling them.

A deadlock is straightforward enough to draw on paper: one process holds a resource another needs, while waiting for something that process holds. What is harder to follow in a static diagram is how the system got there. A request can succeed, leave a process waiting, or be refused because granting it would make the state unsafe. Releasing a resource can unblock another process, which may complete its work and release even more resources. Deadlock Playground lets you follow those changes through a live Resource Allocation Graph, process and resource panels, and a history of recorded events.

The other half of the project was figuring out how to model all of this in Java. Processes, resources, operation sequences, prevention rules, detection strategies, recovery policies, and historical states have different responsibilities. We used that separation to work through the OOAD side of the problem, from identifying domain classes to deciding how they collaborate when a single user action changes several parts of the simulation.

### Understanding Deadlocks

#### Resource Allocation Graph

A Resource Allocation Graph (RAG) shows the relationship between processes and resources. Processes appear as circles, resource types as rectangles, and resource instances as dots inside those rectangles. A directed edge from a process to a resource means the process is waiting for an instance; an edge in the other direction means an instance has been allocated to that process.

For example, if `P1` holds `R1` and requests `R2`, while `P2` holds `R2` and requests `R1`, the graph forms a cycle. With one instance of each resource, neither process can finish, so that cycle confirms a deadlock. With multiple instances, the same kind of cycle does not necessarily mean deadlock: another available instance or a process that can finish might still allow the system to make progress.

The graph is derived from the simulator's current process and resource state. It is not a second model of allocations maintained separately by the frontend. That matters when the user steps through an operation or revisits an earlier event: the graph and the numerical state are describing the same moment.

#### The Four Coffman Conditions

A deadlock requires four conditions to hold together: **mutual exclusion**, **hold and wait**, **no preemption**, and **circular wait**. At least one resource must be exclusive; a process must be allowed to keep what it holds while requesting more; resources cannot simply be taken away; and the waiting dependencies must form a cycle.

The simulator has prevention settings that let you experiment with breaking each condition. Marked shareable resource types can be granted concurrently when the mutual-exclusion control is enabled. Hold-and-wait prevention makes consecutive scripted requests an atomic group rather than allowing a process to collect only part of that group. Release When Blocked can make a waiting requester give up its existing holdings, and circular-wait prevention enforces a fixed order in which resources may be requested.

These choices change the rules of the simulation, so they are selected when configuring a scenario. A prevention setting is not a post-processing fix for a deadlock that has already occurred.

#### Deadlock Prevention

Prevention stops particular request patterns from creating all four Coffman conditions at once. Resource ordering is a good example: if processes can only request resources in increasing configured order, one process cannot hold a higher-ranked resource while waiting to go back to a lower-ranked one. That removes the possibility of a circular wait under that rule.

The other controls have costs of their own. Requiring a whole group of resources at once can leave a process waiting even when some of the resources are free. Returning holdings when blocked may undo useful allocation progress. The simulator applies these rules during request handling, so you can see which requests are granted, delayed, or rejected rather than assuming prevention is free.

#### Deadlock Avoidance

Avoidance is different from prevention. It does not ban an entire pattern of requests; it checks whether a particular grant would leave the system in a **safe state**. Deadlock Playground uses the Banker's safety algorithm for this optional mode. Before granting a request, it tests whether the unfinished processes could complete in some order using the available resources and the resources returned by earlier completions.

Each process's maximum claim is derived from the highest number of instances it would hold at once in its configured request-and-release sequence. A safe tentative grant can proceed. An unsafe request stays pending without changing existing allocations, even if the requested instances are currently available.

Avoidance is off by default. That demonstration mode is intentional: if every unsafe grant were refused, it would be difficult to construct deadlocks and then inspect how detection and recovery behave.

#### Deadlock Detection

The simulator uses different detection approaches depending on resource instance counts. When every resource type has one instance, `CycleDetection` examines the Resource Allocation Graph for cycles. When any resource type has multiple instances, `BankersDetection` instead reasons about current outstanding requests, exclusive allocations, available instances, and what processes able to finish could release.

This distinction keeps a structural cycle from being reported as a confirmed deadlock in cases where the processes can still make progress. Detection identifies the affected processes and updates their deadlock indicators. A process can be `WAITING` without being deadlocked; those are separate facts in the model.

#### Deadlock Recovery

Once a deadlock is confirmed, recovery needs to answer two questions: **which process should be affected, and what should happen to it?** Deadlock Playground provides three victim-selection policies: Priority, Resources Held, and Recovery Cost. The last one is the default and scores a candidate using its held resource units plus the number of scripted instructions it has successfully executed.

The chosen process can be **terminated**, releasing everything it holds and stopping its execution, or **preempted**, releasing its holdings and restarting its scripted work from the beginning. Preemption here is a restart inside the simulation, not restoration from an operating-system checkpoint. After each intervention, the engine tries pending requests again and runs detection once more. Recovery can take several rounds, and it reports when a deadlock remains unresolved.

### Object-Oriented Analysis and Design

#### From Use Cases to Classes

We started with the actions the user needs: configure a scenario, request and release resources, step through operations, detect and recover from deadlocks, inspect history, and reset. Looking at the nouns in those use cases gives obvious candidates such as Process, Resource, Scenario, Operation, and Snapshot. The harder part is deciding which nouns deserve their own objects.

A resource instance, for example, does not need a separate Java object when its only relevant property is a count. An allocation is a relationship between a process and a resource type, not necessarily an `Allocation` class. A deadlock is a detection result, not a permanent object in the scenario. The [analysis documentation](docs/analysis.md) walks through those distinctions, including the noun analysis and use-case reasoning.

#### Domain Modeling and Class Relationships

`Scenario` contains the processes, resource types, configured operations, and prevention settings for one trial. A `Process` keeps its priority, lifecycle state, holdings, pending demand, script position, and executed-instruction count. `Resource` handles its instance capacity and availability. The `SimulationEngine` retains an active scenario when one is configured; Reset can leave it with no scenario at all.

These are different kinds of relationships. A scenario has strong ownership of its configured processes and resources, while a process's resource holdings are conceptually many-to-many associations recorded using IDs and counts. A `HistoryEvent` references a `Snapshot`, and several events may refer to the same snapshot when they describe one committed transition. The [domain model](docs/domain-model.md) explains the multiplicities, ownership, encapsulation, and interface relationships in more detail.

#### Responsibility Assignment

The design uses CRC-style reasoning: what does each class know, what does it do, and which other objects does it need? `Resource` tracks capacity, `Process` tracks its execution state, and `Scenario` keeps the configured trial together. The engine coordinates a command, but the detector should not choose a recovery victim, and a victim-selection policy should not terminate a process.

That division matters in a request that triggers more than one change. Releasing a resource may wake a waiting process; the resulting grant may complete its script; completion may return more resources. The engine has to coordinate the full transition without making the HTTP handler or graph renderer responsible for resource accounting.

#### Design Patterns

Two design patterns have concrete jobs here. **Strategy** separates behavior that varies: `DetectionStrategy` has `CycleDetection` and `BankersDetection`; `VictimPolicy` has `PriorityPolicy`, `ResourcesHeldPolicy`, and `RecoveryCostPolicy`; and `RecoveryStrategy` has `Terminate` and `Preempt`. These are Java interface implementations, not unrelated branches in one monolithic algorithm.

**Facade** appears in `SimulationEngine`, which provides a common entry point for the simulator's main operations. The HTTP layer passes a command to the appropriate session's engine instead of implementing prevention, detection, recovery, and history management itself. This is a coordinating boundary, not a claim that the engine has no complexity. The [design patterns documentation](docs/design-patterns.md) covers the implementations, alternatives, and trade-offs.

#### Object Collaboration and State Management

A process has four lifecycle states: `RUNNING`, `WAITING`, `COMPLETED`, and `TERMINATED`. Whether a process is confirmed deadlocked is a separate flag. This avoids treating every wait as a deadlock or adding lifecycle states the simulator does not actually use.

History is modeled as recorded events with restorable snapshots, not just a list of messages. Snapshots capture the mutable execution state needed to move backward or forward: holdings, availability, pending requests, lifecycle states, script positions, and executed-instruction counts. Choosing an earlier event restores that state without replaying every command. If a new operation is committed from that point, the later events are replaced by a new branch; simply viewing history does not delete them.

These behaviors create a useful OOAD problem of their own: an operation and its history must agree even when one action causes several grants or state changes. The [object collaboration documentation](docs/object-collaboration.md) follows resource requests, recovery, snapshots, and state transitions through the relevant objects.

#### Layered Architecture

The browser interface uses HTML, CSS, and vanilla JavaScript to collect input and render the Resource Allocation Graph with Cytoscape.js and Dagre. The Java HTTP layer validates requests, maps JSON, manages browser sessions, and forwards commands to `SimulationEngine`. The engine coordinates use cases against the domain model, where the resource rules, detection logic, recovery policies, and history data live.

Each browser session has its own in-memory engine and history. Tabs that share the same session cookie share a trial; separate sessions do not. The simulation state is temporary, so server restarts and hosting inactivity spin-downs clear it. The [architecture documentation](docs/architecture.md) covers these boundaries, while [design decisions](docs/design-decisions.md) discusses coupling, cohesion, applicable SOLID principles, and the trade-offs behind the implementation.

The public [OOAD documentation](docs/README.md) also includes the [use case](docs/diagrams/use-case.svg), [class](docs/diagrams/class-diagram.svg), [object](docs/diagrams/object-diagram.svg), [resource-request sequence](docs/diagrams/sequence-request.svg), [recovery sequence](docs/diagrams/sequence-recover.svg), [state machine](docs/diagrams/state-process.svg), [activity](docs/diagrams/activity-workflow.svg), and [architecture](docs/diagrams/architecture.svg) diagrams.

### Features

#### Scenario Configuration

Create processes with priorities, define resource types and instance counts, mark shareable resources, and write ordered request-and-release sequences. Configure prevention rules and optionally enable Banker's avoidance before starting a trial.

#### Interactive Resource Allocation Graph

The graph shows processes, resources, held instances, and pending requests as the simulation changes. Its highlighting distinguishes structural cycles from confirmed deadlocked processes, which is especially important with multi-instance resources.

#### Step-by-Step Simulation

Run requests and releases manually or advance configured operation sequences one step at a time. Blocked requests stay pending, and returned resources can wake processes that were unable to continue earlier.

#### Deadlock Management

Run detection when you want to inspect the current state. If a deadlock exists, compare victim-selection policies and choose termination or preemption to see how recovery changes the allocation graph and process states.

#### Event History and Time Travel

Move backward or forward through recorded events, jump to a specific snapshot, and branch the simulation by taking a new action from an earlier state. Reset clears the current session's scenario and history.

### Tech Stack

| Tech | Usage |
|---|---|
| Java 17 | Domain model, simulation engine, deadlock handling, and HTTP server |
| Maven | Java build, dependency management, tests, and executable JAR packaging |
| Gson | JSON parsing and serialization between the browser and backend |
| JUnit 5 | Automated tests for simulation logic and API behavior |
| HTML, CSS, JavaScript | Browser interface and interactive controls without a frontend framework |
| Cytoscape.js | Resource Allocation Graph visualization |
| Dagre | Automatic layout of graph nodes and edges |
| Docker | Containerized build and runtime |
| Render | Hosting the public web application |

### Project Link

**Live application:** [https://deadlock-playground.onrender.com/](https://deadlock-playground.onrender.com/)