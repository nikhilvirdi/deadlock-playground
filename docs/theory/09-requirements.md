# 9. Requirements

This specification describes the completed release of Deadlock Playground. Existing requirement IDs are retained. FR-06 and FR-45 are retired and listed under Out of Scope. The Verification Status section identifies open acceptance checks.

## Functional Requirements

### Configuration (UC1)

- **FR-01.** The user can name a scenario and add, rename, or remove process entries before submitting the configuration.
- **FR-02.** The user can set an integer priority per process. The default priority is 1.
- **FR-03.** The user can add, rename, or remove resource types and set each type's total instances and shareable flag.
- **FR-04.** The user can define an ordered script of resource requests and releases for each process, including the count per operation. Scripts may be empty.
- **FR-05.** The API shall reject malformed JSON, missing required fields, invalid field types, blank names or identifiers, duplicate IDs within a process or resource list, unknown resource references, and invalid counts. Names and IDs shall contain at most 128 characters and no control characters. A scenario shall contain 1 to 32 processes and 1 to 32 resource types, with 1 to 256 instances per type and at most 2,048 script operations across all processes. A single request may not exceed its resource type's total capacity. An invalid configuration shall leave the active simulation intact.

### Request and Release (UC2, UC3)

- **FR-07.** The system shall grant a valid request by a running process only if the active prevention rules permit it and the required exclusive instances are available. With avoidance enabled, a grant also requires a safe tentative allocation.
- **FR-08.** A request that remains blocked for capacity shall leave the process in Waiting, retain its pending demand, and appear as a request edge for each demanded exclusive resource. The manual Request API shall distinguish a grant, a capacity block, an unsafe-state denial, and a prevention rejection as separate outcomes in successful HTTP responses. Invalid inputs, including claims exceeded in avoidance mode, shall return an API error.
- **FR-09.** When a tentative allocation is unsafe, the system shall deny the grant, provide the safety-check reason, and queue the pending request for re-evaluation. The denial shall leave existing holdings and available counts intact.
- **FR-10.** A valid release shall update the process's holdings and return exclusive instances to the available pool. The system shall grant eligible pending requests in configured process order, subject to capacity and any enabled safety check. When an awakened process finishes its script and returns holdings, earlier waiters shall be reconsidered.
- **FR-11.** The system shall reject any release exceeding the process's holdings, including a release of a resource it does not hold. The rejection shall leave state and history intact.

### Avoidance and Prevention

- **FR-12.** The user can enable Banker's avoidance when configuring a scenario. In this mode, the system shall derive each process's maximum claim per resource from its peak outstanding scripted holdings, accounting for scripted releases. Manual and scripted requests shall stay within those claims. The safety check shall compare remaining claims for exclusive resources with availability and releasable holdings after the tentative grant. It shall provide a safe sequence or an unsafe-state reason. Demo mode, the default, shall skip claim enforcement and safety checking while applying the configured prevention rules and capacity constraints.
- **FR-13.** The user can set all four prevention controls in the scenario form. Submitting a configuration starts a new trial with those settings.
- **FR-14.** With Mutual Exclusion prevention enabled, marked shareable types shall allow concurrent holdings without depleting pooled availability, subject to each process's capacity and applicable claim limits. Unmarked types shall remain exclusive. With the control disabled, all types shall use exclusive accounting.
- **FR-15.** With Hold and Wait prevention enabled, consecutive scripted requests shall form one atomic acquisition with no partial grant. A process shall hold no resources at the start of a new scripted group or manual request.
- **FR-16.** Release When Blocked shall target the no-preemption condition. If a request cannot be satisfied for lack of exclusive capacity, the system shall return the requester's holdings and queue their reacquisition together with the new demand, provided the combined demand fits the configured capacities. Otherwise the prevention rule shall reject the request. The explicit recovery strategy Preempt shall be available with either setting.
- **FR-17.** With Circular Wait prevention enabled, a process shall request resources in strictly increasing configuration order, relative to its current holdings and within a scripted group. When a prevention rule rejects a manual request, the system shall preserve state and history. When a prevention rule rejects a scripted request, the system shall stop that script, terminate the process, release its holdings, record the outcome, and reconsider waiting requests.

### Simulation (UC4)

- **FR-18.** Step shall execute the next operation of the first running process with remaining scripted work, in configured process order. One Step may execute an atomic request group under FR-15.
- **FR-19.** Step shall report the script finished when no nonterminal process has remaining scripted work. If scripted work remains but no process can run it, the Step API shall report that execution is waiting or deadlocked without declaring completion. The browser shall enable Step only when its synchronized state contains runnable scripted work.
- **FR-20.** A blocked scripted request shall retain its operation index and group length. Other runnable scripts can proceed. A delayed grant shall advance the original script for the operations granted. Granting a pending manual request shall leave the script cursor unchanged.
- **FR-21.** The system shall display Running, Waiting, Completed, and Terminated as process states, with a separate deadlock indicator. A process configured with an empty script shall be available for manual requests. When a process executes its final scripted operation, including a delayed grant, it shall become Completed and release its remaining holdings. Completed and Terminated processes shall hold no resources and contribute no remaining runnable work.

### Detection (UC5)

- **FR-22.** Detect shall use resource-allocation graph cycle detection when every resource type has one instance.
- **FR-23.** Detect shall use Banker's-style reduction when any resource type has multiple instances. It shall use available exclusive capacity, current exclusive holdings, and outstanding requests. A structural cycle alone is insufficient to confirm deadlock in a multi-instance scenario.
- **FR-24.** Detect shall report the strategy used, whether it found a deadlock, and the affected process IDs. The single-instance detector can also return a representative cycle. A multi-instance result may supply only the affected process set.
- **FR-25.** The graph shall distinguish structural cycle edges from confirmed deadlock indicators. Confirmed processes shall have stronger outlines and text labels, mirrored in the Processes panel. Confirmed single-instance cycle edges shall receive stronger emphasis. Detection shall refresh process flags, and subsequent committed simulation changes shall re-evaluate an existing marked deadlock.
- **FR-26.** A Detect command that finds no deadlock, including when no process waits, shall succeed and report a negative result and record a detection event, subject to the history budget.

### Recovery (UC6)

- **FR-27.** The user can choose Priority, Resources Held, or Recovery Cost. Among eligible victims, Priority selects the lowest numeric priority and Resources Held selects the fewest held instances. Recovery Cost is the default.
- **FR-28.** Recovery Cost shall select the minimum sum of total held instances and successfully executed scripted instructions. Each operation in a successfully completed atomic group shall count once, including when granted after a wait. Manual operations shall leave the instruction count unchanged.
- **FR-29.** Each victim policy shall break score ties by lexicographic process ID, choosing the first under Java string ordering.
- **FR-30.** Terminate shall release all holdings, clear the victim's pending request and deadlock flag, and set the victim to Terminated.
- **FR-31.** Preempt shall use a held, exclusive resource identified by the detector as contributing to a blocking cycle. It shall return all of the victim's holdings and restart that process in Running with its script cursor, instruction count, waiting request, and deadlock flag reset.
- **FR-32.** Recovery shall choose from the detector's eligible cycle-contributing holders. After each intervention, recovery shall reconsider waiting requests and repeat detection. It shall run another round only while a deadlock remains and a useful victim can be selected.
- **FR-33.** Recovery shall report its rounds and whether the deadlock was resolved. If rounds commit, history shall record the interventions and the actual resolved or unresolved outcome. An attempt that completes no intervention shall preserve state and history. A Recover command with no deadlock shall report that recovery is unnecessary.

### Reset (UC7)

- **FR-34.** Reset shall discard the requesting session's active scenario, including processes, resources, allocations, waits, execution progress, detection and recovery display, graph, and history. The view shall return to its unconfigured state with no new history event or initial snapshot. Other sessions shall be unaffected.
- **FR-35.** Reset shall require confirmation if the current history contains more than one recorded event, including later events when viewing an earlier snapshot. Reset with only the initial configuration event shall proceed without a prompt. Cancelling shall preserve the trial.

### Event History

- **FR-36.** A newly configured scenario shall start with one history event. Committed transitions shall record their events in sequence with coherent, restorable snapshots. Multiple event descriptions from one transition may reference the same stable snapshot. Snapshots shall retain allocation, availability, process state, waiting provenance, script cursors, executed-instruction counts, and deadlock flags.
- **FR-37.** Step Back, Step Forward, and event selection shall restore the corresponding snapshot and synchronize the selected event, graph, Processes and Resources panels, and operation progress. Navigation by itself shall preserve subsequent recorded events.
- **FR-38.** A command that commits new events from an earlier snapshot shall replace its future history. A rejected or failed command with no committed transition shall preserve the selected state and its future. A recorded block or unsafe denial can branch history because it changes the simulation state. A successful Detect command can also branch history by recording its result.

### Visualisation and Interface

- **FR-39.** The Resource Allocation Graph shall display process nodes as ellipses, resource types as rectangles, and total resource instances as dots. The graph shall include disconnected nodes and preserve user-defined display labels. Process nodes, resource nodes, and edges shall use distinct internal ID namespaces.
- **FR-40.** Requests shall appear as dashed directed edges from processes to resources. Allocations shall appear as solid directed edges in the reverse direction. Edges for multiple instances shall display the count. Allocations shall include shared holdings. Request edges shall represent only pending exclusive demand.
- **FR-41.** The graph shall reflect the same committed or selected state as the other panels, including after a restore. With no active scenario it shall show an empty state.
- **FR-42.** The interface shall provide Configure, Request, Release, Step, Detect, Recover, Reset, and history controls. Actions requiring an active scenario shall be unavailable before configuration. Step shall follow FR-19, history controls shall reflect the selected position and available events, and command submission shall be unavailable while a command is pending or the view is unsynchronized.
- **FR-43.** The Processes panel shall show priorities, states, holdings, the pending resource and count when present, and remaining operation counts. The Resources panel shall show totals, available instances, and shareability. Event History shall show step, event type, description, and selection position.
- **FR-44.** The interface shall show actionable validation feedback and distinguish success, warning, error, and informational outcomes in text. Simulation feedback shall appear near the relevant controls and be cleared when a subsequent operation or history selection supersedes it.
- **FR-46.** If graph dependencies fail to load or the renderer fails, the graph shall show an unavailable message while configuration, simulation controls, state panels, and history remain usable.

### Browser Sessions and State Synchronization

- **FR-47.** The server shall scope simulation reads, configuration, mutations, Reset, and history navigation to the session identified by the browser's cookie. Each independently issued session shall have its own engine, state, and history. Tabs that share the same site cookie shall share that session.
- **FR-48.** The server shall create an in-memory session on successful initial configuration. It shall allow up to 16 concurrent retained sessions, refuse new sessions at capacity, and expire sessions after 30 idle minutes once they have no in-flight request. Reset shall clear the simulation while retaining the session. Restart shall clear all sessions.
- **FR-49.** Refreshing within an active session shall reconstruct the submitted name, resources, capacities, shareability, processes, priorities, scripts, and prevention and avoidance settings from the server. It shall preserve execution progress, graph, event history, and selected snapshot without submitting a scenario or adding events. A session with no active scenario shall show the default form.
- **FR-50.** The browser shall obtain state and history as one revisioned view, discard stale responses, and refresh after commands that change the revision. The server shall reject a history selection submitted with an obsolete view revision, and the browser shall refresh the view. If synchronization fails, the browser shall display a recovery message and disable further commands until synchronization is restored.

## Non-Functional Requirements

### Correctness

- **NFR-01.** Apply cycle detection, multi-instance reduction, and Banker's safety checking using the inputs and preconditions in FR-12 and FR-22 through FR-24. For each exclusive resource, available instances plus all current holdings shall equal total capacity. For shared resources, availability shall remain at total capacity and each process's holding shall stay within that capacity.
- **NFR-02.** Maintain automated coverage for both detection algorithms, the safety algorithm, all victim policies, and both recovery strategies. Include regression checks for accounting, waiting and completion, transactional history and branching, API validation, session isolation, and frontend state synchronization.

### Design Quality

- **NFR-03.** The Java domain model shall remain independent of HTTP handling and the browser.
- **NFR-04.** The backend shall be authoritative for allocations, scheduling, detection, safety, and recovery. The frontend shall submit commands and render backend state and results.
- **NFR-05.** Detection, victim selection, and recovery shall use their respective Strategy interfaces. Algorithm selection shall remain explicit in the engine for detection and recovery and in the HTTP adapter for victim policy selection.

### Usability

- **NFR-06.** A user shall be able to configure two processes and two single-instance resources manually, issue requests to form a deadlock in demo mode, and detect it using the on-screen controls.
- **NFR-07.** The interface shall use a classic, predominantly monochromatic visual design with subdued semantic colors for success, warning, and error text.
- **NFR-08.** The interface shall combine non-color graph cues with visible textual identification of confirmed deadlocked processes.

### Accessibility

- **NFR-09.** Enabled simulation controls, form controls, and event selection shall be reachable and operable by keyboard with visible focus. Feedback and history position shall use accessible text and status announcements where applicable.
- **NFR-10.** Interface text shall meet a 4.5:1 contrast ratio against its background, including semantic status text. This requirement remains unmet for disabled button labels.

### Performance

- **NFR-11.** The performance target is under one second each for Configure, Step, and Detect, measured from activation to the updated view on a defined student-laptop reference setup with 10 processes and 10 resource types. The reference scenario and setup must be recorded with the result. Benchmark verification remains open.
- **NFR-12.** History navigation shall restore a snapshot without replaying earlier operations.

### Portability

- **NFR-13.** The Maven build shall target Java 17. Windows, macOS, and Linux with a compatible Java runtime and a modern browser are intended platforms. Release acceptance across those platforms remains open.
- **NFR-14.** Maven shall package the standalone Java HTTP application with its frontend and graph dependencies. Core simulation shall require no database, remote service, or CDN. Optional web fonts shall fall back to local fonts.

### Maintainability

- **NFR-15.** The backend shall expose simulation use cases through the SimulationEngine Facade. The HTTP adapter shall mediate browser access. The Facade and Strategy relationships shall be described in the [class diagram design notes](07-class-diagram.md).
- **NFR-16.** Java class names shall follow PascalCase. Methods and fields shall use camelCase, with uppercase snake case for constants.

### Reliability and Security

- **NFR-17.** The server shall enforce a 256 KiB request body limit and the scenario limits in FR-05. History shall be bounded by a scenario-dependent memory budget and a 2,048-event ceiling. When a command would exceed the budget, it shall be rejected before mutation.
- **NFR-18.** The server shall require JSON on mutation routes and enforce its configured Host and supplied Origin and Fetch Metadata checks. Session cookies shall be host-only, HttpOnly, and SameSite=Strict, with Secure required in public HTTPS mode. Commands for one session shall be serialized independently of other sessions.
- **NFR-19.** Static serving shall be limited to supported asset types under the frontend root. Traversal and real-path escapes shall be rejected. Dynamic user-supplied and server-supplied text shall be rendered as text.

## Verification Status

The existing Maven suite exercises engine, model, HTTP, and session behavior. The [browser regression script](../../tests/browser/history-sync.cjs) exercises history and refresh synchronization, stale responses, graph identities and fallback, and session startup. Remaining acceptance items are listed below.

- **NFR-10 is unmet.** The default disabled button text uses 50% opacity, giving approximately 3.36:1 contrast for the dark label on a white panel. Full contrast verification remains open.
- **NFR-11 is unverified.** The one-second target needs a measured benchmark on a defined reference setup.
- **NFR-13 is partially verified.** The Maven compiler target is Java 17. Runtime and browser acceptance across the intended platform matrix remains open.

### Disposition of Original Requirements

| Disposition | Original IDs |
| --- | --- |
| Implemented as specified in substance, with clarifications | FR-02, FR-04, FR-10, FR-11, FR-13, FR-16, FR-17, FR-21 through FR-23, FR-26 through FR-30, FR-32, FR-37, FR-39, FR-40, FR-42, FR-43 |
| Partially described the implementation and now specify the applicable conditions or observable behavior | FR-01, FR-03, FR-05, FR-07 through FR-09, FR-12, FR-14, FR-15, FR-18 through FR-20, FR-25, FR-31, FR-33, FR-35, FR-36, FR-41, FR-44 |
| Incorrect for the completed release and corrected at the same ID | FR-24, FR-34, FR-38 |
| Unsupported and retired from the active requirements | FR-06, FR-45 |
| Retained or clarified | NFR-01 through NFR-04, NFR-09, NFR-12, NFR-14, NFR-16 |
| Revised to match implemented usability, design, or extension points | NFR-05 through NFR-08, NFR-15 |
| Retained with a known contrast shortfall | NFR-10 |
| Retained as acceptance requirements with verification open | NFR-11, NFR-13 |

## Out of Scope

- **FR-06 (retired).** Loading textbook or other preset scenarios.
- **FR-45 (retired).** In-app learning documentation pages. Coursework theory is maintained as standalone repository documentation.
- Authenticated accounts, registration, permanent simulation storage, databases, and distributed session storage.
- Real operating-system resource management.
- Performance dashboards, utilisation analytics, and automatic playback with Start and Pause controls.
- Livelock and starvation simulation, and general checkpoint rollback. History navigation and the victim restart in FR-31 are supported forms of restoration.
