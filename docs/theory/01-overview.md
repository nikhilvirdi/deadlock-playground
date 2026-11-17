# 1. Project Overview

## Problem

In an operating system, processes compete for shared resources such as printers, files, and memory. A deadlock happens when a group of processes each hold a resource and wait for a resource held by another process in the same group. None of them can move forward, so they wait forever.

Suppose R1 and R2 each have one instance. P1 holds R1 and requests R2, while P2 holds R2 and requests R1. Both processes remain blocked.

Textbooks usually show deadlock as a static diagram, which makes it hard to see how a circular dependency develops. The interactive Resource Allocation Graph (RAG) lets students follow that dependency as requests and allocations change.

## What the Simulator Does

1. **Configure a scenario.** Define processes and their priorities, resource types and their instance counts and shareability, and any scripted sequences of requests and releases. Choose the prevention and avoidance settings for the scenario.
2. **Execute operations.** Request or release resources manually, or use Step to advance the configured sequences. The graph shows processes and resource types, with dots for resource instances and directed edges for requests and allocations.
3. **Detect deadlocks.** The detector uses cycle detection when every resource type has a single instance. With multiple instances, a structural cycle can occur even when processes can still finish, so detection evaluates current requests, allocations, and available instances. The graph and Processes panel mark confirmed deadlocked processes.
4. **Recover.** Select the Priority, Resources Held, or Recovery Cost policy to choose a victim. Termination stops the victim and releases its resources. Preemption releases the victim's resources and resets it to the start of its operation sequence. The simulator checks for remaining deadlocks after each recovery round.
5. **Review the event history.** Move backward or forward through recorded snapshots, or select an event directly. A valid action that records a new event from an earlier snapshot creates a branch and replaces the later events. Reset discards the active scenario, its simulation state, and its history, returning the application to the unconfigured state.

**Banker's avoidance** is optional and enabled during scenario configuration. It derives each process's declared maximum resource claims from the greatest number of instances of each resource it would hold at once in its operation sequence. In this mode, a grant requires a safe sequence. With avoidance disabled, demonstration mode permits unsafe allocations and lets users construct deadlocks when the chosen prevention settings allow them.

Four prevention controls illustrate how changing a Coffman condition affects the simulation.

- **Mutual Exclusion** permits sharing for resource types marked shareable.
- **Hold and Wait** requires a process to release its holdings before starting a new request group. Consecutive scripted requests in a group are granted together.
- **Release When Blocked** targets the no-preemption condition. If a request must wait for an unavailable resource, the process returns its holdings and waits to reacquire them together with its new request.
- **Circular Wait** imposes the configured resource order. A process can request only resources ranked above those it already holds.

## Users

- Students learning operating systems.
- Instructors demonstrating deadlock in class.

## Scope

Deadlock Playground is a Java 17 application developed for Object-Oriented Analysis and Design coursework. Its browser interface lets students and instructors examine how deadlocks arise and compare ways to prevent, avoid, detect, and recover from them.

### Browser sessions

Browser clients with separate sessions have independent scenarios and histories. The server keeps these sessions temporarily in memory. A page refresh displays the current state of the same session. Idle sessions expire, and a server restart clears all sessions.

### Coursework theory

The separate coursework theory documents discuss checkpoint rollback, livelock, and starvation at the operating-system level.

### Out of scope

- Performance dashboards and utilisation analysis.
- Automatic playback, textbook scenario presets, and dedicated in-app learning pages.
- User accounts, authentication, databases, and permanently saved simulations.
- Real operating-system integration and general-purpose process checkpoint rollback.
