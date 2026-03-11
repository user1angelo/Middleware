# OpenDaylightModule Plugin (SDK-Based UDM)

This document explains how the **OpenDaylightModule** works as an SDK-based pluggable module, how it is packaged into `opendaylight-module.jar`, and how it is intended to be loaded by the Module Registry & Lifecycle Manager.

## 1. Role in the System

The OpenDaylightModule is a **security response module** that connects the SOAR platform to an OpenDaylight SDN controller. It is responsible for interpreting mitigation-related events (such as `INITIATE_MITIGATION` and `SDN_INSTALL_FLOW`) and translating them into SDN actions (e.g. host isolation, IP blocking) on the network layer.

In this iteration, the module uses a **stubbed implementation**: it subscribes to events and logs what it *would* do against OpenDaylight, without making real RESTCONF calls. This is sufficient for panel demos and workflow validation; concrete RESTCONF integrations can be added later.

## 2. SDK-Based Design

`OpenDaylightModule` is implemented as a **SOAR SDK plugin**:

- Package: `com.nis1.thesis.udm.OpenDaylightModule`
- Implements: `com.nis1.thesis.sdk.PluggableModule`
- Uses: `CoreSystemApi`, `Event`, `MitigationCommandData`, `MitigationAction`, and `ModuleHelper` from `nis-thesis-sdk`.

The lifecycle is managed by the core system:

- The **Lifecycle Manager** discovers the module JAR and loads `OpenDaylightModule` via reflection.
- It calls `initialize(CoreSystemApi api)` **once** at startup.
- It calls `shutdown()` during a graceful stop.
- The module never opens its own RabbitMQ connections; all messaging goes through the SDK’s `CoreSystemApi`.

## 3. Event Subscriptions

On initialization, the module registers two subscriptions via `CoreSystemApi`:

- `INITIATE_MITIGATION`
  - Payload type: `MitigationCommandData` (SDK)
  - Intended producer: Workflow Engine (e.g. from `ransomware_detection_prtg.yml` or SDN workflows)
  - Handler: `onMitigationCommand(Event<?> event)`
  - Behavior (stubbed): inspects `targetHost`, `action`, `workflowInstanceId`, and `justification`, then logs how it would apply the requested mitigation in OpenDaylight.

- `SDN_INSTALL_FLOW`
  - Payload type: TBD (for now treated as a generic object)
  - Intended producer: Workflow Engine (SDN-focused workflows like `flow-based-host-isolation-workflow.yml`)
  - Handler: `onSdnInstallFlow(Event<?> event)`
  - Behavior (stubbed): logs the payload type and content to show what would be converted into a RESTCONF flow.

The module uses `ModuleHelper` for structured logging, e.g.:

- `helper.log(getName(), "INFO", "Initializing OpenDaylightModule (id=...)");`
- `helper.log(getName(), "WARN", "MitigationAction QUARANTINE is not yet implemented in stub");`

## 4. Configuration

The module reads its configuration from:

- `user-defined-modules/config/opendaylight-module.properties`

Key properties used by the plugin:

```properties
# OpenDaylight RESTCONF
odl.base_url=http://opendaylight:8181
odl.username=admin
odl.password=admin

# Module identity
module.id=odl_sdn_01
module.name=OpenDaylight SDN Module
module.type=sdn_controller
```

RabbitMQ-related keys in this file (`rabbitmq.*`) are **ignored** by the SDK-based implementation; messaging is handled solely through `CoreSystemApi`.

If the file is missing, the module falls back to built-in defaults and logs a warning.

## 5. Thin Plugin JAR Layout

The plugin is packaged as a **thin JAR**:

- File: `user-defined-modules/opendaylight-module.jar`
- Contains only: compiled classes under `com/nis1/thesis/udm/OpenDaylightModule.class`
- **Does not include**: any `com.nis1.thesis.sdk.*` classes (they must be on the host’s classpath).

This means:

- The **Module Registry / Lifecycle Manager** process is responsible for having the `nis-thesis-sdk` classes on its own classpath (e.g., via `nis-thesis-sdk` JAR or compiled classes).
- The plugin JAR stays small and avoids SDK version conflicts between modules.

## 6. Building the Plugin JAR

From the repository root for `user-defined-modules`:

```bash
cd user-defined-modules

# 1) Compile SDK sources and OpenDaylightModule using the same javac
mkdir -p target/classes
javac -cp "../ModuleRegistryLifecycleManager/lib/*" \
  -d target/classes \
  ../nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/*.java \
  src/main/java/com/nis1/thesis/udm/OpenDaylightModule.java

# 2) Package a thin plugin JAR containing only the module class
jar cf opendaylight-module.jar -C out com/nis1/thesis/udm/OpenDaylightModule.class
```

Notes:

- Step 1 compiles both the SDK and `OpenDaylightModule` into the local `out/` directory so that the class file versions match. The SDK classes are **only** used at compile-time here; they are not placed inside the JAR.
- Step 2 creates `opendaylight-module.jar` that contains just the plugin class hierarchy under `com/nis1/thesis/udm/`.

## 7. Loading the Plugin

The intended flow (high level):

1. `ModuleRegistryMain` / Lifecycle Manager starts with `nis-thesis-sdk` on its classpath.
2. It scans a configured directory (`user-defined-modules/`) for JAR files.
3. For each JAR, it uses a dedicated `URLClassLoader` to load candidate classes.
4. It searches for classes that implement `PluggableModule` (from the SDK).
5. When it finds `com.nis1.thesis.udm.OpenDaylightModule`, it:
   - Instantiates it via reflection.
   - Calls `initialize(CoreSystemApi api)` exactly once.
6. During system shutdown, it calls `shutdown()` on the module instance.

Because the JAR is thin and the SDK lives in the host process, there is only a **single source of truth** for interfaces like `CoreSystemApi` and `PluggableModule`.

## 8. Future Enhancements

Planned next steps for `OpenDaylightModule`:

- Replace the stubbed `simulateBlockIp(...)` method with real RESTCONF calls to OpenDaylight, using the configured `odl.base_url`, `odl.username`, and `odl.password`.
- Define a dedicated POJO for SDN flow installation requests and wire it into `SDN_INSTALL_FLOW` events.
- Add structured audit/logging events (e.g. `AUDIT_LOG`, `SDN_FLOW_INSTALLED`) using SDK `Event<Payload>` types instead of ad-hoc JSON.

This structure allows you to keep the **interface and lifecycle stable** while iterating on the SDN integration details underneath.