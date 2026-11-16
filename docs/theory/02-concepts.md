# 2. Key Concepts

## Coffman Conditions

A deadlock can occur only when all four conditions hold at the same time:

1. Mutual exclusion: at least one resource is non-shareable.
2. Hold and wait: a process holds one resource while it waits for another.
3. No preemption: a resource can be released only voluntarily by the process holding it.
4. Circular wait: a cycle of processes exists, where each one waits for a resource held by the next.

The prevention toggles target individual conditions. Sharing applies only to resource types that can be shared.

## Resource Allocation Graph (RAG)

A directed graph with two kinds of node:

- Process: drawn as a circle.
- Resource type: drawn as a rectangle, with one dot per instance.

There are two kinds of edge:

- Request edge: from a process to a resource. The process is waiting for an instance.
- Allocation edge: from a resource instance to a process. The process holds that instance.

A cycle in the RAG is necessary for deadlock. Whether it is also sufficient depends on the number of instances.

## Single-Instance vs Multiple-Instance Detection

- Single-instance: each resource type has exactly one instance. A cycle in the RAG is both necessary and sufficient for deadlock, so cycle detection is enough.
- Multiple-instance: a graph cycle alone is insufficient. Detection tries to satisfy current outstanding requests with available instances and allocations that finishable processes can return. Waiters with no exclusive holdings cannot sustain a deadlock.

## Deadlock Avoidance (Banker's Algorithm)

When Banker's avoidance is enabled, the system tests the proposed allocation against the declared maximum claims before granting it. A safe sequence allows every unfinished process to reach its remaining claim and return its holdings. An unsafe request waits while allocations stay unchanged. Demo mode allows unsafe grants so that detection and recovery can be exercised.

## Deadlock Prevention

The simulator offers four ways to target the Coffman conditions.

- Mutual exclusion: mark a resource as shareable, so grants never block on it.
- Hold and wait: a process requests a group of resources atomically and begins that group with no holdings.
- No preemption: Release when blocked returns a requester's holdings on a capacity shortage. The requester waits to reacquire them together with the new request.
- Circular wait: enforce a global resource ordering. A process may only request resources ranked above the ones it already holds.

## Recovery

Once a deadlock is detected, it can be broken in one of two ways:

- Process termination: a deadlocked process is killed and all its resources are released.
- Resource preemption: a resource is taken from a process, which is rolled back to its initial state.

Victim selection decides which process to sacrifice. It uses a policy such as priority, resources held, or recovery cost.

## Other Concepts Covered in the In-App Docs

- Livelock: processes keep changing state in response to each other but make no progress.
- Starvation: a process waits indefinitely because others are always given preference.
- Rollback: restoring a process to a saved checkpoint, instead of its initial state.
