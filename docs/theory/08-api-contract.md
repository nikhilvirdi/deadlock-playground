# 8. JSON API Contract

This file defines the HTTP interface between the Java backend (`com.sun.net.httpserver`) and the browser frontend (vanilla JS with Cytoscape.js). Both sides build against this document, so the shapes below are fixed unless this file is changed first.

## General Rules

- Base URL: `http://localhost:8080/api`
- All request and response bodies are JSON (UTF-8).
- Process IDs and resource IDs are strings, such as `"P1"` and `"R2"`.
- Every mutating endpoint returns the full updated state, so the frontend never has to merge partial data.
- Every successful response has `"ok": true`. Failed responses have `"ok": false` and an `error` object.

### Input and processing limits

- POST bodies are limited to 256 KiB of UTF-8 JSON. Larger bodies return HTTP 413 with `PAYLOAD_TOO_LARGE`. Integers must be JSON numbers with exact values in the signed 32-bit range. Totals and operation counts must be positive. Priorities may be negative or zero.
- A scenario supports up to 32 processes, 32 resource types, 256 instances per resource type, and 2,048 scripted operations across all processes. Scenario names and IDs must be nonblank, at most 128 characters, and free of control characters.
- Event history can hold up to 2,048 events. An action reserves space for its possible events before it runs and returns HTTP 409 with `HISTORY_LIMIT` if there is too little room. No snapshots are evicted. Earlier snapshots remain accessible and a successful action from an earlier snapshot can branch as usual.
- The OpenJDK server defaults are eight request workers, 16 queued tasks, 32 connections, a five-second request timeout, and a ten-second response timeout. Request and response deadlines use built-in JVM properties and close the connection when exceeded. Saturation can still cause a dropped connection. Engine state access remains serialized.

### Error Shape

```json
{
  "ok": false,
  "error": {
    "code": "PREVENTION_VIOLATION",
    "message": "Request P2 to R1 is out of global order under circular-wait prevention."
  }
}
```

Error codes:

| Code | Used when |
|---|---|
| `VALIDATION_ERROR` | Duplicate IDs, zero instances, or a request larger than the total instances (UC1, alternate 5a). |
| `PREVENTION_VIOLATION` | A prevention rule rejects a request (UC2, alternate 2a). |
| `PREEMPTION_VIOLATION` | Reserved for preemption errors. Recovery without an eligible victim reports `resolved: false`. |
| `NOT_HELD` | A release targets a resource the process does not hold (UC3, alternate 1a). |
| `NO_OPERATIONS` | Step is called when the scenario is finished (UC4, alternate 2a). |
| `NO_DEADLOCK` | Recover is called without a detected deadlock (UC6 precondition). |
| `INVALID_POLICY` | An unknown victim policy or recovery strategy name is given. |
| `INVALID_HISTORY_INDEX` | A history jump points outside the recorded events. |

## State Object

Every endpoint returns this object as `data.state`. It is the single source the frontend renders from.

```json
{
  "scenario": { "name": "Textbook figure", "bankersAvoidance": false },
  "step": 4,
  "prevention": {
    "mutualExclusion": false,
    "holdAndWait": false,
    "noPreemption": false,
    "circularWait": false
  },
  "processes": [
    {
      "id": "P1",
      "priority": 2,
      "state": "WAITING",
      "deadlocked": false,
      "held": [ { "resource": "R2", "count": 1 } ],
      "waitingFor": { "resource": "R1", "count": 1 },
      "pendingOperations": 2
    }
  ],
  "resources": [
    { "id": "R1", "name": "R1", "total": 1, "available": 0, "shareable": false }
  ],
  "graph": {
    "nodes": [
      { "data": { "id": "P1", "label": "P1", "type": "process" } },
      { "data": { "id": "R1", "label": "R1", "type": "resource", "instances": 1 } }
    ],
    "edges": [
      { "data": { "id": "e1", "source": "R2", "target": "P1", "kind": "allocation", "inCycle": false } },
      { "data": { "id": "e2", "source": "P1", "target": "R1", "kind": "request", "inCycle": false } }
    ]
  }
}
```

Field notes:

- `step` is the viewed position, the sequence of the event the state corresponds to.
- `state` is one of `RUNNING`, `WAITING`, `COMPLETED`, `TERMINATED`. `deadlocked` is a separate flag. `pendingOperations` is zero for completed and terminated processes.
- `graph` follows the Cytoscape.js `data` wrapper, so it can be passed to Cytoscape without conversion.
- `inCycle` is true for edges that belong to a detected cycle. It is false until detection runs.

## Endpoints

### GET /api/state

Returns the current state.

Response:

```json
{ "ok": true, "data": { "state": { } } }
```

### POST /api/scenario (UC1: Configure)

Loads a scenario from the form or a preset.

Request, custom scenario:

```json
{
  "name": "My scenario",
  "bankersAvoidance": false,
  "prevention": { "mutualExclusion": false, "holdAndWait": false, "noPreemption": false, "circularWait": false },
  "resources": [ { "id": "R1", "total": 1, "shareable": false } ],
  "processes": [
    {
      "id": "P1",
      "priority": 1,
      "operations": [
        { "type": "REQUEST", "resource": "R1", "count": 1 },
        { "type": "RELEASE", "resource": "R1", "count": 1 }
      ]
    }
  ]
}
```

Request, preset:

```json
{ "preset": "TEXTBOOK_FIGURE" }
```

Response: `{ "ok": true, "data": { "state": { } } }`. History is reset, and the initial snapshot becomes the first event.

Note: presets are not implemented yet and return `VALIDATION_ERROR`.

`bankersAvoidance` defaults to `false` for deadlock demonstrations and is returned under `state.scenario`. Enabling it derives fixed maximum claims from the peak outstanding instance counts in each script. Manual requests exceeding a declared claim return `VALIDATION_ERROR`. Prevention flags are set through this endpoint. `noPreemption: true` means Release when blocked and leaves explicit Preempt recovery available.

### POST /api/request (UC2: Request resource)

Request:

```json
{ "process": "P2", "resource": "R1", "count": 1 }
```

Response:

```json
{
  "ok": true,
  "data": {
    "outcome": "GRANTED",
    "reason": "Granted 1 of R1 to P2",
    "safety": null,
    "state": { }
  }
}
```

`outcome` is one of `GRANTED`, `BLOCKED`, `DENIED_UNSAFE`, `REJECTED_PREVENTION`. When the request runs a safety analysis, `safety` contains `safe`, `sequence` and `blockingReason`. A safe grant provides a sequence and a null blocking reason. An unsafe denial provides a blocking reason and a null sequence. Otherwise `safety` is null.

### POST /api/release (UC3: Release resource)

Request:

```json
{ "process": "P2", "resource": "R1", "count": 1 }
```

Response: `{ "ok": true, "data": { "unblocked": ["P1"], "state": { } } }`

`unblocked` lists processes whose pending requests were granted because of this release. A process that finishes its script with the grant moves directly to Completed.

### POST /api/step (UC4: Step)

Request: empty body `{}`.

Response:

```json
{
  "ok": true,
  "data": {
    "executed": { "description": "Granted 1 of R1 to P1" },
    "finished": false,
    "state": { }
  }
}
```

### POST /api/detect (UC5: Detect deadlock)

Request: empty body `{}`.

Response:

```json
{
  "ok": true,
  "data": {
    "strategy": "CYCLE",
    "deadlocked": true,
    "processes": ["P1", "P2"],
    "cycle": ["P1", "R1", "P2", "R2", "P1"],
    "state": { }
  }
}
```

`strategy` is `CYCLE` for single-instance resources and `BANKERS` otherwise. `cycle` contains one cycle for a positive `CYCLE` result and is null otherwise. The `BANKERS` process list excludes waiters with zero exclusive holdings.

### POST /api/recover (UC6: Recover)

Request:

```json
{ "policy": "RECOVERY_COST", "strategy": "TERMINATE" }
```

`policy` is `PRIORITY`, `RESOURCES_HELD`, or `RECOVERY_COST` (default). `strategy` is `TERMINATE` or `PREEMPT`.

Response:

```json
{
  "ok": true,
  "data": {
    "rounds": [
      { "victim": "P2", "strategy": "TERMINATE", "resolvedAfter": true }
    ],
    "resolved": true,
    "state": { }
  }
}
```

`rounds` holds one entry per victim. Both strategies choose victims that hold a contended exclusive resource on a blocking cycle. `resolved` is false if the system could not clear the deadlock.

### POST /api/reset (UC7: Reset)

Request: `{ "confirmed": true }`

If the history is not empty and `confirmed` is false, the response is `VALIDATION_ERROR` with a message asking for confirmation. This matches alternate flow 1a.

Response: `{ "ok": true, "data": { "state": { } } }`

### GET /api/history

Returns the event list.

Response:

```json
{
  "ok": true,
  "data": {
    "events": [
      { "seq": 0, "type": "REQUEST", "description": "P1 requests R1" },
      { "seq": 1, "type": "GRANT", "description": "R1 granted to P1" }
    ]
  }
}
```

### POST /api/history/jump

Restores the snapshot stored at one event, so the user can step backward or click any event.

Request: `{ "seq": 1 }`

Response: `{ "ok": true, "data": { "state": { } } }`

Jumping does not delete later events. The history stays read-only, as decided in the design.

## Ownership

- Backend: owns every field in the state object. The frontend never computes allocation, detection, or safety results.
- Frontend: sends user actions and renders the returned state. It owns layout and styling only.
