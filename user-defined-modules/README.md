# User-Defined Modules (UDM)

This directory contains user-defined modules that integrate external systems
with the Middleware stack (ThreatContextStore, WorkflowEngine, ModuleRegistry & Lifecycle Manager).

For the PRTG/OpenDaylight ransomware use case, we introduce two modules:

- **PRTGModule** – standalone Java process that receives HTTP notifications from PRTG
  and publishes standardized `alerts.host.prtg` events into the Message Bus.
- **OpenDaylightModule** – **SDK-based pluggable module** that subscribes to
  mitigation-related events (e.g. `INITIATE_MITIGATION`, `SDN_INSTALL_FLOW`) via
  `CoreSystemApi` and will push isolation rules to an OpenDaylight SDN controller
  (RESTCONF implementation is currently stubbed for simulation/demo).

These modules follow the JSON/event model defined in:

- `ModuleRegistryLifecycleManager/SDK_Detailed_Context.md`
- `ModuleRegistryLifecycleManager/PRTG_OpenDaylight_Events.md`

and the message types used by the ModuleRegistry & WorkflowEngine.

> NOTE: `PRTGModule` is currently a standalone RabbitMQ-connected process, while
> `OpenDaylightModule` is now implemented as a `PluggableModule` using the
> `nis-thesis-sdk`. The lifecycle manager is responsible for discovering and
> initializing SDK-based modules.

## Directory Layout

```text
user-defined-modules/
  README.md
  src/
    main/
      java/
        com/
          nis1/
            thesis/
              udm/
                PRTGModule.java
                OpenDaylightModule.java
```

## Build (manual example)

Compile all UDM classes, ensuring both the Module Registry libraries and the
`nis-thesis-sdk` JAR are on the classpath (after building the SDK with Maven):

```bash
cd user-defined-modules
javac -cp "../ModuleRegistryLifecycleManager/lib/*:../nis-thesis-sdk/target/*" \
  -d target/classes src/main/java/com/nis1/thesis/udm/*.java
```

### Running modules

`PRTGModule` remains a standalone process and can be run directly:

```bash
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*" com.nis1.thesis.udm.PRTGModule
```

`OpenDaylightModule` is intended to be loaded by the Module Registry & Lifecycle
Manager as an SDK-based plugin, so it does **not** expose a standalone `main`
entry point anymore. Package it into a JAR and place it in the directory that
your lifecycle manager scans for pluggable modules (for this project, typically
`user-defined-modules/`).

> These commands assume RabbitMQ and PostgreSQL are configured as per the
> existing Middleware components and that `ModuleRegistryMain` and
> `WorkflowEngineMain` are already running.
