# Middleware System + SDK Thesis Context (Working Draft)


## 1) Project Summary

This repository implements a security automation “middleware” that connects detection sources, storage/analytics, orchestration logic, and response modules using an event-driven architecture. The SDK is the developer-facing contract that makes response/enrichment modules pluggable and consistent across the system.

**Core idea:** everything is an event, and modules are built around publish/subscribe patterns rather than direct service-to-service RPC.

**Key directories**
- `nis-thesis-sdk/`: the SDK (interfaces + shared payload models)
- `ModuleRegistryLifecycleManager/`: module registry, routing, health monitoring, and SDK module host
- `WorkflowEngine/`: YAML-driven orchestration engine that emits workflow commands
- `ThreatContextStore/`: alert/query persistence and query response publishing (PostgreSQL)
- `user-defined-modules/`: example modules (PRTG, Suricata, Zeek, OpenDaylight integration)
- `webapp/`: operator dashboard (Node.js backend + React frontend)

---

## 2) System Architecture (Mental Model)

At runtime, the system behaves like a SOAR-style pipeline:

1. **Detectors / integrations produce alerts** (Wazuh, Zeek, Suricata, PRTG, etc.)
2. **RabbitMQ transports events** (decoupling producers from consumers)
3. **ThreatContextStore persists alerts** and can answer queries (PostgreSQL JSONB)
4. **WorkflowEngine matches alerts to workflows** (YAML “policy as code”) and emits commands
5. **ModuleRegistry routes commands** to appropriate modules based on capabilities
6. **Response modules execute actions** (e.g., SDN isolation via OpenDaylight)
7. **Webapp monitors and controls** services, workflows, and module health

The “supporting system” exists primarily to make the SDK useful:
- the registry provides lifecycle + discovery + routing
- the workflow engine provides automation logic (“what to do”)
- the bus + schemas provide interoperability (“how to talk”)
- the store + UI provide visibility and investigation (“what happened”)

---

## 2.1) Data Lifecycle (Modules → SDK → RabbitMQ → Distribution → YAML)

This subsection focuses strictly on how data moves through the system, step by step.

1. Module produces domain data
   - A detector or integration module transforms tool-native output into a normalized payload.
   - Standalone UDM example: Zeek turns notice.log entries into `alerts.network.zeek`.
     See [ZeekModule.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/user-defined-modules/src/main/java/com/nis1/thesis/udm/ZeekModule.java).

2. SDK wrapping
   - Inside SDK-based modules, payloads are wrapped in a typed envelope: `Event<T>`.
   - Publish via `CoreSystemApi.publishEvent(...)`; subscribe via `CoreSystemApi.subscribeToEvent(...)`.
   - References: [Event.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/Event.java),
     [CoreSystemApi.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/CoreSystemApi.java),
     [PluggableModule.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/PluggableModule.java).

3. RabbitMQ serialization
   - The SDK host or UDM converts `Event<T>` into the canonical JSON envelope and publishes to RabbitMQ.
   - Example publish path in the embedded host: [SdkModuleHost.publishEvent](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java#L62-L94).
   - Envelope shape:
     ```json
     {
       "message_type": "alert",
       "event_id": "<uuid>",
       "timestamp": "<ISO-8601>",
       "event_type": "alerts.host.wazuh",
       "source_module": "SdkModuleHost|<UDMName>",
       "payload": { "..." }
     }
     ```

4. Distribution to proper channels
   - Registry receives and fans out alerts so storage and orchestration remain decoupled.
   - Conceptually:
     - ThreatContextStore consumes “alerts” → persists to PostgreSQL
     - WorkflowEngine consumes the same alerts → drives playbooks
   - Registry details and message types: [ModuleRegistryLifecycleManager README](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/README.md#L96-L169).
   - ThreatContextStore ingest/response pattern: [ThreatContextStore README](file:///c:/Users/keanl/Documents/GitHub/Middleware/ThreatContextStore/README.md).

5. YAML workflows (policy as code)
   - Workflows declare triggers and steps. When an alert matches a trigger, the engine instantiates a run and executes steps.
   - Action steps emit “workflow_command” messages with an `event_type` matching a module capability (e.g., `INITIATE_MITIGATION`).
   - Command emission logic: [WorkflowExecutor](file:///c:/Users/keanl/Documents/GitHub/Middleware/WorkflowEngine/src/main/java/com/yourorg/workflow/WorkflowExecutor.java#L104-L139).
   - Engine behavior narrative: [WorkflowEngine.md](file:///c:/Users/keanl/Documents/GitHub/Middleware/WorkflowEngine/WorkflowEngine.md).

6. Command routing to modules
   - Registry routes workflow commands to the specific module based on capability.
   - The embedded SDK host deserializes JSON back into SDK types and dispatches to subscribed listeners:
     [SdkModuleHost.dispatch](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java#L139-L176)
     and mitigation payload parsing [parseMitigationCommand](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java#L204-L256).

7. Module executes and may publish follow-up events
   - Response modules act (e.g., SDN isolation) and can publish enrichment/confirmation alerts, which re-enter the same bus-driven lifecycle.

### YAML example (trigger + action with data substitution)

Workflows are stored under [WorkflowEngine/workflows](file:///c:/Users/keanl/Documents/GitHub/Middleware/WorkflowEngine/workflows). A simplified pattern:

```yaml
name: general_ransomware_response
version: 1.0
trigger:
  event_type: alerts.host.wazuh
  conditions:
    severity: high
    alert_type: ransomware_detection
steps:
  - name: isolate_host
    action:
      event:
        type: INITIATE_MITIGATION
        data:
          targetHost: "{{ trigger.payload.source_ip }}"
          action: "ISOLATE_VLAN"
          justification: "Auto-response: ransomware detection"
```

Notes:
- `event_type` under `trigger` determines subscription.
- `{{ trigger.payload.* }}` are template variables substituted from the incoming alert; see substitution in
  [WorkflowExecutor.extractVariableValue](file:///c:/Users/keanl/Documents/GitHub/Middleware/WorkflowEngine/src/main/java/com/yourorg/workflow/WorkflowExecutor.java#L176-L220).
- Emitted command uses the action’s `event.type` which must match a registered module capability.

---

## 3) Communication Fabric (RabbitMQ)

RabbitMQ acts as the central message bus. Components exchange JSON messages that share a consistent envelope.

### 3.1 Canonical JSON envelope

Multiple components follow the same shape (exact fields vary slightly by message type, but the envelope is consistent):

```json
{
  "message_type": "alert | registration | heartbeat | workflow_command | query | query_response | ...",
  "event_id": "uuid-or-trace-id",
  "timestamp": "ISO-8601 timestamp",
  "event_type": "routing.key.like.alerts.host.wazuh or INITIATE_MITIGATION",
  "source_module": "string identifier",
  "payload": { "type-specific content": "..." }
}
```

Examples are documented in [ModuleRegistryLifecycleManager README](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/README.md#L96-L169) and [ThreatContextStoreGuide.md](file:///c:/Users/keanl/Documents/GitHub/Middleware/ThreatContextStore/ThreatContextStoreGuide.md).

### 3.2 Queues (conceptual)

From the repository docs and code, the architecture uses multiple queues for separation of concerns:
- An “alerts ingestion” path for storage and historical analytics (ThreatContextStore)
- A “workflow path” for orchestration triggers (WorkflowEngine)
- A “workflow response / command” path that carries workflow-emitted commands (ModuleRegistry routes to modules)
- Optional query request/response queues for analytics and investigation (ThreatContextStore)

The registry’s role is to fan-out alerts and route commands so that the store and workflow engine can operate independently without tight coupling.

---

## 4) Supporting Core Components

### 4.1 ThreatContextStore (Storage + Queries)

**Purpose:** durable storage of alerts (and query tracking) in PostgreSQL, plus a mechanism for executing structured queries and publishing results.

**Key behaviors (from docs):**
- consumes `alert` and `query` messages
- stores alerts/queries in PostgreSQL
- publishes query results to a response queue and writes result files

Reference: [ThreatContextStore README](file:///c:/Users/keanl/Documents/GitHub/Middleware/ThreatContextStore/README.md).

**Why it matters for the SDK thesis:** the SDK standardizes event payloads and types so that stored events remain queryable across many different modules/tools.

### 4.2 WorkflowEngine (Policy-as-Code Orchestration)

**Purpose:** execute YAML workflows as automation playbooks.

Conceptually:
- it discovers workflow triggers by parsing YAML files
- subscribes to trigger event types
- when a matching alert arrives, it instantiates an in-memory workflow instance and executes steps
- action steps produce new events/commands, wait_for steps block on expected responses/timeouts

Repository narrative: [WorkflowEngine.md](file:///c:/Users/keanl/Documents/GitHub/Middleware/WorkflowEngine/WorkflowEngine.md).

**Command publishing mechanics (important for SDK integration):**
- workflow steps emit events that are turned into JSON “workflow_command” messages
- the emitted `event_type` is intended to match a module capability

Implementation reference: [WorkflowExecutor.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/WorkflowEngine/src/main/java/com/yourorg/workflow/WorkflowExecutor.java#L104-L139).

### 4.3 ModuleRegistry & Lifecycle Manager (Discovery + Routing + Health)

**Purpose:** the “broker” that:
- registers modules and persists their metadata
- monitors module liveness (heartbeats)
- broadcasts alerts to the rest of the platform
- routes workflow commands to the correct module based on capability matching

Reference: [ModuleRegistryLifecycleManager README](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/README.md).

**Persistence model:**
- in-memory map for fast lookups
- PostgreSQL table for durability across restarts

Implementation reference: [ModuleRegistry.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/ModuleRegistry.java#L18-L121).

---

## 5) SDK: What It Is and Why It’s the Center of the Thesis

The SDK lives in `nis-thesis-sdk/` and provides the core contracts and shared models that make modules pluggable.

In thesis terms, the SDK is the “extensibility boundary” of the platform:
- it defines what a module is
- it defines how modules communicate (publish/subscribe abstraction)
- it provides shared payload types for interoperability

### 5.1 Core contracts

#### PluggableModule

The lifecycle contract for a module:
- `getName()` identifies the module for logs/UI
- `initialize(CoreSystemApi api)` injects the messaging gateway and is the module’s startup hook
- `shutdown()` provides a deterministic cleanup hook

Reference: [PluggableModule.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/PluggableModule.java).

#### CoreSystemApi

The SDK’s messaging gateway abstraction:
- `publishEvent(Event<?>)`: send events into the fabric
- `subscribeToEvent(String, Consumer<Event<?>>)`: register interest in types/patterns

Reference: [CoreSystemApi.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/CoreSystemApi.java).

### 5.2 Event envelope (typed, immutable)

SDK modules use a strongly-typed event envelope (`Event<T>`) even though the system transports JSON.

This design creates a useful separation:
- **on the wire**: JSON for interoperability
- **in module code**: typed payload objects for safety and developer ergonomics

Reference: [Event.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/Event.java).

### 5.3 Shared payload models

The SDK provides reusable data contracts, including (non-exhaustive):
- `HostAlertData`, `NidsAlertData`
- `EnrichmentRequestData`, `IpReputationData`
- `MitigationCommandData`, `MitigationAction`

These models encode interoperability decisions: fields, naming conventions, and expected semantics.

Example reference: [MitigationAction.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/MitigationAction.java).

### 5.4 Developer ergonomics: ModuleHelper

`ModuleHelper` is a convenience wrapper used to reduce boilerplate and standardize common publishing patterns and logging.

Reference: [ModuleHelper.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/ModuleHelper.java).

### 5.5 “SDK as an API boundary” (thesis framing)

The SDK lets you argue clear contributions:
- a stable plugin lifecycle contract
- a standardized event schema for cross-tool communication
- a capability-based routing model enabled by consistent event types
- a developer workflow for building new integrations quickly

This is the point where you can align with related work:
- SOAR platforms and playbook automation
- event-driven microservice design
- plugin architectures and dependency inversion
- SDN-based automated response

---

## 6) How SDK Modules Are Hosted (Two Models in This Repo)

This repo contains two “module styles”, both relevant to the thesis because they demonstrate trade-offs in deployment.

### 6.1 Standalone user-defined modules (external processes)

Some modules are implemented as standalone Java programs that manage their own RabbitMQ connectivity, registration, and heartbeats.

Example: [ZeekModule.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/user-defined-modules/src/main/java/com/nis1/thesis/udm/ZeekModule.java).

**Strengths**
- isolation: module failures don’t crash the registry
- independent scaling and deployment

**Costs**
- more boilerplate (connection management, heartbeats)
- more operational complexity

### 6.2 Embedded SDK modules (hosted inside the registry process)

The ModuleRegistry project also contains an embedded SDK host (`SdkModuleHost`) that instantiates `PluggableModule` classes directly and provides them with a `CoreSystemApi` implementation that bridges to RabbitMQ for publishing, and dispatches incoming JSON messages to module listeners.

Reference: [SdkModuleHost.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java).

This host is important for the SDK thesis because it shows how:
- the SDK contracts remain stable
- multiple hosting implementations can exist behind `CoreSystemApi`
- deployment can be simplified when you want “modules as libraries” instead of “modules as services”

---

## 7) Example End-to-End Flow (Ransomware → SDN Mitigation)

One representative narrative for a thesis chapter:

1. A detector (e.g., Wazuh/PRTG/Suricata/Zeek integration) publishes an `alert` with `event_type` such as `alerts.host.wazuh`.
2. The registry broadcasts the alert to storage and orchestration queues.
3. WorkflowEngine matches the alert to a ransomware workflow YAML trigger.
4. WorkflowEngine executes steps and emits a `workflow_command` with an `event_type` that matches a module capability (e.g., `INITIATE_MITIGATION`).
5. ModuleRegistry routes the command to the module that registered for that capability.
6. The SDN module (OpenDaylight integration) translates the mitigation request into controller actions (RESTCONF, flow rules, VLAN isolation, etc.).

Supporting references:
- command creation logic: [WorkflowExecutor.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/WorkflowEngine/src/main/java/com/yourorg/workflow/WorkflowExecutor.java#L104-L139)
- SDK module hosting + mapping: [SdkModuleHost.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java#L139-L256)
- SDN plugin notes: [OpenDaylightModule_Plugin.md](file:///c:/Users/keanl/Documents/GitHub/Middleware/user-defined-modules/OpenDaylightModule_Plugin.md)

---

## 8) Webapp (Operator Dashboard)

The webapp provides observability and control:
- start/stop services
- inspect logs and module status
- edit workflows/configuration from a browser

Directory: `webapp/` with `backend/` (Node/Express) and `frontend/` (React).

The frontend includes SDK overview/reference pages that summarize the same SDK contracts used in Java:
- [SdkOverview.js](file:///c:/Users/keanl/Documents/GitHub/Middleware/webapp/frontend/src/pages/SdkOverview.js)
- [SdkCoreApi.js](file:///c:/Users/keanl/Documents/GitHub/Middleware/webapp/frontend/src/pages/SdkCoreApi.js)

In thesis terms, this UI is evidence of operational maturity: your platform is not only an SDK but also a usable system with status visibility and repeatable demos.

---

## 9) Build & Run (Repo-Constrained View)

This repo is a multi-module workspace with Java and Node subprojects.

**SDK build**
- SDK is a Maven project: `nis-thesis-sdk/pom.xml`

**Java services**
- `ThreatContextStore` and `WorkflowEngine` include docs showing `javac`-based builds (with `lib/*`)
- `ModuleRegistryLifecycleManager` is Maven-based
- `user-defined-modules` is Maven-based

**Webapp**
- root `package.json` bootstraps installs in `webapp/backend` and `webapp/frontend`

Top-level overview: [README.md](file:///c:/Users/keanl/Documents/GitHub/Middleware/README.md).

---

## 10) Thesis Writing Guidance (How to Turn This Into Chapters)

If your thesis is SDK-centered, a clean structure is:

1. **Problem statement:** heterogeneous security tools, manual response, lack of reusable integrations
2. **Related work:** SOAR, EDR/SIEM integration patterns, event-driven systems, SDN response
3. **Design goals:** interoperability, modularity, fault tolerance, extensibility, observability
4. **System design:** bus + schemas + orchestrator + registry + store + UI
5. **SDK design (main contribution):**
   - lifecycle contract (`PluggableModule`)
   - messaging gateway (`CoreSystemApi`)
   - typed envelope (`Event<T>`) and shared models
   - module patterns (enrichment, mitigation, correlation)
6. **Implementation:** show how the SDK is hosted and how workflows emit SDK-aligned commands
7. **Evaluation:** demonstration scenario, success criteria, latency/throughput observations, failure modes
8. **Limitations & future work:** schema governance, versioning, security hardening, scaling strategy

---

## 11) Source Index (Starting Points)

- Project overview: [README.md](file:///c:/Users/keanl/Documents/GitHub/Middleware/README.md)
- SDK deep technical doc: [SDK_Detailed_Context.md](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/SDK_Detailed_Context.md)
- System component narrative: [Script.txt](file:///c:/Users/keanl/Documents/GitHub/Middleware/Script.txt)
- Module registry docs: [ModuleRegistryLifecycleManager README](file:///c:/Users/keanl/Documents/GitHub/Middleware/ModuleRegistryLifecycleManager/README.md)
- ThreatContextStore docs: [ThreatContextStore README](file:///c:/Users/keanl/Documents/GitHub/Middleware/ThreatContextStore/README.md)
- Workflow narrative: [WorkflowEngine.md](file:///c:/Users/keanl/Documents/GitHub/Middleware/WorkflowEngine/WorkflowEngine.md)
- SDK contracts:
  - [PluggableModule.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/PluggableModule.java)
  - [CoreSystemApi.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/CoreSystemApi.java)
  - [Event.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/Event.java)
  - [ModuleHelper.java](file:///c:/Users/keanl/Documents/GitHub/Middleware/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/ModuleHelper.java)

