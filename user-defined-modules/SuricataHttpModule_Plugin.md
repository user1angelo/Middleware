# SuricataHttpModule Plugin (SDK-Based UDM)

This document explains how the **SuricataHttpModule** works as an SDK-based pluggable module, how it is packaged into `suricata-http-module.jar`, and how it is intended to be loaded by the Module Registry & Lifecycle Manager.

## 1. Role in the System

The SuricataHttpModule is a **network detection module** that connects the SOAR platform to Suricata via HTTP. Instead of tailing `eve.json` from disk, Suricata (or a small forwarder) sends **JSON-formatted eve events over HTTP**, and this module converts them into standardized `alerts.network.suricata` events.

## 2. SDK-Based Design

`SuricataHttpModule` is implemented as a **SOAR SDK plugin**:

- Package: `com.nis1.thesis.udm.SuricataHttpModule`
- Implements: `com.nis1.thesis.sdk.PluggableModule`
- Uses: `CoreSystemApi`, `Event`, `ModuleHelper` from `nis-thesis-sdk`
- Reuses: `SuricataAlertData` payload class from `user-defined-modules`

The lifecycle is managed by the core system:

- The **Lifecycle Manager** discovers the plugin JAR and loads `SuricataHttpModule` via reflection.
- It calls `initialize(CoreSystemApi api)` **once** at startup.
- It calls `shutdown()` during a graceful stop.
- The module never opens its own RabbitMQ connections; all messaging goes through the SDK’s `CoreSystemApi`.

## 3. HTTP Endpoint

On initialization, the module starts an embedded `HttpServer` and exposes an HTTP endpoint for Suricata events.

Configuration (from `user-defined-modules/config/suricata-http-module.properties`):

```properties
suricata.http.host=0.0.0.0
suricata.http.port=8090
suricata.http.path=/suricata/alerts

module.id=suricata-http-module
module.name=Suricata HTTP NIDS Module
module.type=network_security
```

Behavior:

- Listens for `POST` requests on `http://<host>:8090/suricata/alerts`
- Expects `Content-Type: application/json`
- Request body: single **eve.json-style** Suricata event (one JSON object)
- If `event_type == "alert"`, it is converted to `SuricataAlertData` and published as an SDK `Event<SuricataAlertData>` with type `alerts.network.suricata`.

## 4. Event Publishing

For each valid Suricata alert, the module constructs a payload using `SuricataAlertData`:

- Identification:
  - `alertId` – generated ID (`SURI-<timestamp>`)
  - `signatureId` – from `alert.signature_id`
  - `signature` – from `alert.signature`
- Network context:
  - `sourceIp`, `destinationIp`, `sourcePort`, `destinationPort`, `protocol`
- Classification:
  - `severity` – mapped from Suricata numeric severity
  - `category` / `alertType` – from `alert.category` or inferred from signature text
- Scoring:
  - `threatScore` – derived from severity + category
  - `confidenceScore` – fixed high confidence (e.g. 95)
- Optional metadata:
  - `action` – Suricata action (allowed, blocked, etc.)
  - `flowId` – Suricata flow ID

The plugin publishes the event using the SDK:

```java
Event<SuricataAlertData> event = Event.of("alerts.network.suricata", payload);
api.publishEvent(event);
```

The Module Registry then routes this event onto the message bus according to its configuration, making it visible to the WorkflowEngine and ThreatContextStore.

## 5. Configuration

The module reads configuration from:

- `user-defined-modules/config/suricata-http-module.properties`

Keys used by the plugin:

```properties
# HTTP listener
suricata.http.host=0.0.0.0
suricata.http.port=8090
suricata.http.path=/suricata/alerts

# Module identity
module.id=suricata-http-module
module.name=Suricata HTTP NIDS Module
module.type=network_security
```

If the file is missing, the module falls back to built-in defaults and logs a warning.

## 6. Thin Plugin JAR Layout

The plugin is packaged as a **thin JAR**:

- File: `user-defined-modules/suricata-http-module.jar`
- Contains: compiled classes under `com/nis1/thesis/udm/SuricataHttpModule.class` and `SuricataAlertData.class`
- **Does not include**: any `com.nis1.thesis.sdk.*` classes (they must be on the host’s classpath)

This means:

- The **Module Registry / Lifecycle Manager** process is responsible for having the `nis-thesis-sdk` classes on its classpath.
- The plugin JAR stays small and avoids SDK version conflicts.

## 7. Building the Plugin JAR

From the repository root for `user-defined-modules`:

```bash
cd user-defined-modules

# 1) Compile SDK sources and Suricata HTTP module using the same javac
mkdir -p out
javac -cp "../ModuleRegistryLifecycleManager/lib/*" \
  -d out \
  ../nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/*.java \
  src/main/java/com/nis1/thesis/udm/SuricataHttpModule.java \
  src/main/java/com/nis1/thesis/udm/SuricataAlertData.java

# 2) Package a thin plugin JAR containing only the UDM classes
jar cf suricata-http-module.jar \
  -C out com/nis1/thesis/udm/SuricataHttpModule.class \
  -C out com/nis1/thesis/udm/SuricataAlertData.class
```

Notes:

- Step 1 compiles both the SDK and the Suricata UDM classes into `out/` so bytecode versions match. The SDK classes are only used at compile-time here; they are **not** included inside the plugin JAR.
- Step 2 creates `suricata-http-module.jar` that contains just the user-defined module classes; the host process provides the SDK.

## 8. Loading the Plugin

High-level flow:

1. `ModuleRegistryMain` / Lifecycle Manager starts with `nis-thesis-sdk` on its classpath.
2. It scans `user-defined-modules/` for JAR files.
3. For each JAR, it uses a `URLClassLoader` to load candidate classes.
4. It searches for classes that implement `PluggableModule`.
5. When it finds `com.nis1.thesis.udm.SuricataHttpModule`, it:
   - Instantiates it via reflection.
   - Calls `initialize(CoreSystemApi api)`.
6. During system shutdown, it calls `shutdown()` on the module instance.

## 9. Suricata Integration Notes

To integrate with Suricata:

- Configure Suricata (or an intermediate forwarder) to send eve.json alerts as HTTP POST requests to:
  - `http://<module-registry-host>:8090/suricata/alerts`
- Ensure the body of each POST is a single eve.json-style alert record (JSON object).
- Verify that `event_type` is set to `"alert"` for events you want processed.

Once configured, alerts should appear in the SOAR pipeline as `alerts.network.suricata` events.
