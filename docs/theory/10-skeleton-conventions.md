# 10. Project Layout and Naming Conventions

## Folder Layout

```text
deadlock-playground/
  README.md
  .gitignore
  .gitattributes
  backend/
    pom.xml
    src/main/java/com/deadlocksim/
      Main.java
      api/
      engine/
      model/
        detection/
        history/
        recovery/
        victim/
    src/main/resources/
    src/test/java/com/deadlocksim/
      api/
      engine/
      model/
  frontend/
    index.html
    css/
      style.css
    js/
      app.js
      labels.js
      api/
      graph/
      panels/
    vendor/
  docs/
    theory/
    uml/source/
    uml/images/
  tests/
    browser/
      history-sync.cjs
```

The root README gives build and launch instructions. `.gitignore` excludes generated build output and local development files. `.gitattributes` disables line-ending conversion for the vendored graph scripts and license files. The theory documents live under `docs/theory/`. PlantUML sources and exported SVGs live under `docs/uml/source/` and `docs/uml/images/`, respectively. The tracked `backend/src/main/resources/` and `frontend/assets/` directories currently contain only `.gitkeep` placeholders.

## Backend Packages

All application packages are under `com.deadlocksim`.

- **`Main.java`** reads startup settings, starts `ApiServer`, and registers its shutdown hook.
- **`model`** contains the scenario, processes, resources, operations, prevention settings, snapshots, event types, the derived allocation graph, and the safety checker. Its `model.history` package contains `History` and `HistoryEvent`. Detection, recovery, and victim selection each have their own model subpackage.
- **`engine`** contains `SimulationEngine` and its operation results. The engine coordinates configuration, requests and releases, scripted execution, prevention, avoidance, detection, recovery, and snapshot navigation. It records committed transitions in history.
- **`api`** handles HTTP, JSON, and sessions. `ApiServer` registers a single root handler that serves static files and dispatches API requests by path and method. It checks methods, origins, and request bodies before delegating simulation operations to the session's engine. `ApiInput` validates JSON fields, `JsonMapper` maps scenario input and simulation state through Gson, and `ErrorMapper` and `ErrorCode` define API error responses.
- **`api.SessionStore`** holds one in-memory engine and its history per retained browser session. `ApiServer` resolves the session from its cookie and synchronizes requests on that engine. Tabs with the same cookie share a simulation. The store expires idle sessions.

The domain model is independent of the engine, HTTP layer, and browser. The engine uses the model and its algorithms. The API handles transport and serialization, and the browser submits commands and renders the state returned over HTTP.

## Frontend Modules

`frontend/index.html` loads the stylesheet and vanilla JavaScript ES modules directly. The frontend has no framework or bundling step.

- `js/app.js` initializes the interface, coordinates commands and revisioned state refreshes, and updates the panels and graph.
- `js/api/` contains the shared fetch and error handling in `client.js` and the endpoint wrappers in `endpoints.js`.
- `js/graph/` renders the backend's graph data. Cytoscape.js draws the graph, and the Cytoscape Dagre adapter uses Dagre to arrange its connected elements.
- `js/panels/` contains the configuration form, actions, detection and recovery controls, process and resource displays, event history, and shared feedback rendering.
- `js/labels.js` associates form labels with controls when the page loads and when controls are added.
- `vendor/` contains pinned browser distributions of Cytoscape.js, Dagre, and the Cytoscape Dagre adapter. The graph loads these files locally without a CDN. The [vendor notes](../../frontend/vendor/README.md) record their versions and hashes. Licenses and bundled dependency notices are stored beside the scripts.

## Testing

- `backend/src/test/java/com/deadlocksim/model/` tests domain objects, resource accounting, the graph, safety checking, snapshots, and the detection, recovery, victim policy, and history packages.
- `backend/src/test/java/com/deadlocksim/engine/` tests simulation behavior. `AlgorithmRegressionTest` covers algorithm and scheduling edge cases. `HistoryConsistencyTest` checks snapshot restoration and branching.
- `backend/src/test/java/com/deadlocksim/api/` exercises the HTTP endpoints and static serving. `SessionIsolationTest`, `GraphConsistencyTest`, and `HistoryJumpEndpointTest` verify session boundaries, graph data, and historical state through the API.
- `tests/browser/history-sync.cjs` is a separate browser regression check for the packaged frontend. It uses Node.js 22 or newer and Chrome or Chromium to exercise refresh, history navigation, stale responses, graph fallback, and session behavior.

## Build and Packaging

`backend/pom.xml` targets Java 17 and runs the Java tests through Maven Surefire. It packages the frontend under `/frontend/` on the classpath. Maven Shade includes the runtime dependency Gson and sets `com.deadlocksim.Main` as the executable JAR entry point. Maven writes build output to `backend/target/`.

Build and launch from the repository root.

```sh
mvn -f backend/pom.xml clean package
java -jar backend/target/deadlock-playground-1.0.jar
```

The application uses the JDK's built-in HTTP server. `BIND_ADDRESS` and `PORT` default to `127.0.0.1` and `8080`, respectively. By default, the server reads frontend files from the JAR. `FRONTEND_DIR` can select a filesystem directory for development.

## Naming Conventions

- Java classes, interfaces, records, and enum types use PascalCase. Each public top-level type uses a matching filename.
- Java methods and fields use camelCase, as in `selectVictim` and `nextOperationIndex`. Constants and enum members use UPPER_SNAKE_CASE, as in `MAX_PROCESSES` and `WAITING`.
- JSON field names use camelCase, as in `configurationRevision` and `pendingOperations`. Serialized enum names such as `WAITING` retain their uppercase spelling.
- Process and resource IDs are user-defined strings. `P1` and `R1` are examples.
- Frontend functions and variables generally use camelCase. Module filenames and CSS class names use lowercase words with hyphens where needed, as in `graph-view.js` and `history-table`.
- Java test classes use descriptive names ending in `Test`, such as `CycleDetectionTest` and `HistoryConsistencyTest`.

## Pattern Locations

- **Strategy.** `model.detection.DetectionStrategy` is realized by `CycleDetection` and `BankersDetection`. `model.victim.VictimPolicy` is realized by `PriorityPolicy`, `ResourcesHeldPolicy`, and `RecoveryCostPolicy`. `model.recovery.RecoveryStrategy` is realized by `Terminate` and `Preempt`.
- **Facade.** `engine.SimulationEngine` provides the backend interface for simulation use cases. `ApiServer` exposes those use cases to the browser through the HTTP API.
