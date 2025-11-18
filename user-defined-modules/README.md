# User-Defined Modules (UDM)

This directory contains **standalone user-defined modules** that integrate external systems
with the Middleware stack (ThreatContextStore, WorkflowEngine, ModuleRegistry & Lifecycle Manager).

For the PRTG/OpenDaylight ransomware use case, we introduce two modules:

- **PRTGModule** – receives HTTP notifications from PRTG and publishes standardized
  `alerts.host.prtg` events into the Message Bus.
- **OpenDaylightModule** – listens for workflow commands (e.g. `INITIATE_MITIGATION`)
  and pushes isolation rules to an OpenDaylight SDN controller (via RESTCONF, TODO).

These modules follow the JSON event model defined in:

- `ModuleRegistryLifecycleManager/SDK_Detailed_Context.md`
- `ModuleRegistryLifecycleManager/PRTG_OpenDaylight_Events.md`

and the message types used by the ModuleRegistry & WorkflowEngine.

> NOTE: These modules are designed as **standalone Java processes** that connect directly
> to RabbitMQ and the Module Registry. They are not yet wired into any generic
> `PluggableModule` loader; instead, they follow the existing UDM pattern
> (`UDMTester`, `SuricataModule`) while aligning with the SDK’s standardized
> JSON message format.

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

## Build & Run (manual, example)

Compile (adjust classpath to point to RabbitMQ + JSON JARs from existing modules):

```bash
cd user-defined-modules
javac -cp "../ModuleRegistryLifecycleManager/lib/*" \
  -d out src/main/java/com/nis1/thesis/udm/*.java
```

Run PRTG module:

```bash
java -cp "out:../ModuleRegistryLifecycleManager/lib/*" com.nis1.thesis.udm.PRTGModule
```

Run OpenDaylight module:

```bash
java -cp "out:../ModuleRegistryLifecycleManager/lib/*" com.nis1.thesis.udm.OpenDaylightModule
```

> These commands assume RabbitMQ and PostgreSQL are configured as per the
> existing Middleware components and that `ModuleRegistryMain` and
> `WorkflowEngineMain` are already running.
