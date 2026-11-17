# 7. Class Diagram Design Notes

The [class diagram](../uml/source/class-diagram.puml) presents the core domain model. These notes describe the implemented relationships and identify where the diagram reflects an earlier design.

## Relationships

### Application and scenario ownership

- **Creation dependency.** `Main` constructs and starts `ApiServer` and registers its shutdown hook.
- **Server and session store.** `ApiServer` retains one `SessionStore`, schedules expiration, and clears the store on shutdown. This is a retained association with lifecycle responsibility. The public constructor creates the store, and the package-private constructor can accept an existing one.
- **Session composition.** `SessionStore` creates and owns its registered `Session` entries. Each retained session has one `SimulationEngine`. `ApiServer` supplies a fresh configured engine when it registers a browser session, and subsequent requests use that session's engine. Each session has independent simulation state and history, with the same lifetime as its engine in the running application. Idle expiration removes the entry once it has no in-flight requests. Reset clears the active scenario and recorded history and keeps the session and engine available for reconfiguration. Sessions are temporary in-memory records identified by a browser cookie.
- **Current scenario association.** An engine has zero or one active `Scenario`. `JsonMapper` constructs the scenario, and `ApiServer` passes it to `configure`. The engine validates and retains it, replaces it on successful reconfiguration, and clears the reference on Reset. This is a navigable association with responsibility for the active scenario lifecycle.
- **Scenario composition.** Each process and resource type belongs to its configured scenario in the running model. A valid configuration has one or more of each. The scenario also stores the ordered operation lists by process ID. The `Process` stores its script position, executed-instruction count, holdings, and pending request. The scenario coordinates changes to holdings and resource availability.
- **Process and resource association.** The holds and requests relationship is conceptually many-to-many, with `0..*` at each end. Holdings and pending demands store resource IDs and quantities. The scenario manages the resource objects, which can be referenced by several processes.

### History and snapshots

- **Engine and history composition.** `SimulationEngine` creates and retains one committed `History` and one private staging `History`. Reconfiguration and Reset clear the committed history's contents. The diagram shows the committed history.
- **History and event composition.** A `History` creates each event and owns its zero or more entries. `truncateAfter` and `clear` discard the corresponding entries. Commit creates new event objects in the destination history from the staged entries. The open diamond in the diagram represents shared aggregation, so it understates this ownership. [OMG UML 2.5.1](https://www.omg.org/spec/UML/2.5.1/PDF)
- **Event and snapshot association.** Each `HistoryEvent` references exactly one `Snapshot`. A snapshot can have `0..*` referencing events. For example, a pre-command snapshot can exist solely for rollback. At a checkpoint, the engine captures one snapshot and passes the same instance to all events staged at that checkpoint. Committed entries retain those references. Separate captures can describe the same state and still be distinct objects. `Snapshot` uses identity-based equality.

### Algorithm dependencies

- **Safety and detection.** The engine calls the static `SafetyChecker` when avoidance is enabled. It constructs `CycleDetection` or `BankersDetection` for detection based on resource instance counts. The two detector classes realize `DetectionStrategy`, whose `detect` operation takes a `Graph`. `SafetyChecker` and `BankersDetection` share the `BankersCore` calculation with different inputs.
- **Victim selection and recovery.** `PriorityPolicy`, `ResourcesHeldPolicy`, and `RecoveryCostPolicy` realize `VictimPolicy`. `ApiServer` selects and constructs the requested policy and passes it to the engine for that recovery command. The engine constructs `Terminate` or `Preempt` for the selected intervention. Both realize `RecoveryStrategy`, which applies an intervention to a scenario and victim.
- The Strategy interfaces describe three decision families. The dashed hollow-triangle arrows in the diagram express interface realization. The engine's use of `VictimPolicy` is a parameter dependency. Its detection and recovery calls depend directly on the concrete classes it constructs.

## Design Decisions

- **Facade.** `SimulationEngine` provides the backend interface for configuring scenarios, requesting and releasing resources, stepping scripts, detecting and recovering from deadlocks, and navigating history. It coordinates prevention, optional avoidance, and event recording around these operations. The browser uses the HTTP API, where `ApiServer` resolves the session and delegates to its engine.
- **Derived graph.** A `Graph` holds a reference to its source `Scenario` and derives nodes and edges on demand. Typed nodes distinguish process IDs from resource IDs, and `JsonMapper` gives serialized edges a separate namespace. Request edges point from processes to resources. Allocation edges point in the reverse direction. User-provided IDs remain the display labels. Single-instance detection uses structural cycles. Multi-instance detection reads allocations, availability, and outstanding demands from the scenario. The JSON view exposes structural-cycle membership separately from confirmed deadlock flags.
- **Snapshot restoration.** A snapshot records process states, deadlock flags, holdings, available resource counts, script cursors, executed-instruction counts, and pending requests, including manual or scripted provenance and grouped demand. Restoring a snapshot writes runtime data back into the same scenario after checking its process and resource IDs. Scenario configuration and scripts stay on the scenario. Maximum claims are derived from those scripts when needed. Navigation restores state without replay. An action that commits new events from an earlier position replaces later entries. Failed or rejected actions that commit no events preserve the existing history.

## Omitted for Readability

- The diagram omits the HTTP and session classes and the private staging history. Its domain model represents the committed simulation for one session.
- `Operation` appears as a type reference. The implementation uses one value class with `REQUEST` and `RELEASE` variants in `OperationType`. Supporting types include `PreventionToggles`, `Process.PendingRequest`, `Graph.Node`, and `Graph.Edge`.
- Result types without their own class boxes include `RequestResult` and its `RequestOutcome` enum, `SafetyResult`, and `DetectionResult`. The engine also uses `ReleaseResult`, `StepResult`, `DetectReport`, `RecoveryReport`, and `RecoveryRound` for command results.

### Discrepancies in the existing diagram

The diagram shows shared aggregation for history entries, a one-to-one event-to-snapshot association, and solid arrows from the engine to three strategy interfaces. The relationships above describe the implemented ownership, multiplicities, and dependencies. The diagram also omits the `0..1` multiplicity on the engine's current scenario and the retained association from `Graph` to `Scenario`. Some class members reflect the earlier model, including numeric process IDs, operation lists on `Process`, initial snapshot creation on `Scenario`, and the signatures for validation, recovery, graph cycles, and snapshot restoration.
