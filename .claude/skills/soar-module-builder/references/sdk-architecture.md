# How the SOAR middleware actually works

Condensed from `THESIS_SDK_AND_SYSTEM.md` and `SDK_USABILITY_AUDIT.md` (both at the repo root) for
the purpose of building a new module. Read those two documents directly for the full thesis-level
detail and file/line citations — this is the "enough to work confidently" version.

## The pipeline, end to end

```
Tool → Module (Java) → RabbitMQ (workflow_queue) → ModuleRegistry
                                                          │
                                    ┌─────────────────────┼─────────────────────┐
                                    ▼                                           ▼
                          ThreatContextStore                              WorkflowEngine
                          (persists to PostgreSQL)                   (matches alert to YAML triggers)
                                                                              │
                                                                              ▼
                                                                   emits a workflow_command
                                                                              │
                                                                              ▼
                                                                     ModuleRegistry routes
                                                                     by capability match
                                                                              │
                                                                              ▼
                                                                    target module executes
                                                                    (e.g. SDN isolation)
```

Every message on the bus — registration, heartbeat, alert, command — shares one JSON envelope:

```json
{
  "message_type": "alert | registration | heartbeat | workflow_command | ...",
  "event_id": "uuid",
  "timestamp": "ISO-8601",
  "event_type": "alerts.network.suricata | INITIATE_MITIGATION | ...",
  "source_module": "string identifier",
  "payload": { "type-specific content": "..." }
}
```

`AlertEnvelopeBuilder` (`nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/AlertEnvelopeBuilder.java`)
builds this shape for you — use it (`.eventType(...).sourceModule(...).payload(...).build()`)
rather than hand-assembling a `JSONObject`, which four modules did independently before this
builder existed.

## The three integration patterns

**Pattern A — Standalone process (the one this skill's templates target).** A plain Java class
with its own `main()`, its own RabbitMQ `Connection`/`Channel`, its own
registration/heartbeat/command-listener boilerplate, and its own ingestion loop (file-tail, UDP, or
HTTP — the ingestion mechanism doesn't change the pattern). It does **not** implement
`PluggableModule`. Examples: `SuricataModule.java`, `MaltrailModule.java`, `Fail2banModule.java`,
`SysmonModule.java` (`user-defined-modules/src/main/java/com/nis1/thesis/udm/`).

Why this is the default recommendation: adding one requires editing **zero** shared files anywhere
in the system. Confirmed repeatedly (Suricata, then Maltrail, then Fail2ban/Sysmon) — copy an
existing module, rename, adapt the parsing logic, done. `MaltrailModule` took about 7 minutes once
the pattern was understood (`MALTRAIL_DEVELOPER_USABILITY_LOG.md`).

**Pattern B — Embedded event-subscriber.** Implements `PluggableModule`
(`nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/PluggableModule.java` — `getName()` /
`initialize(CoreSystemApi)` / `shutdown()`), is loaded in-process by `SdkModuleHost`, and works by
subscribing to event types via `CoreSystemApi.subscribeToEvent(...)` and reacting when dispatched
to. Example: `OpenDaylightModule.java`, which subscribes to `INITIATE_MITIGATION` and drives actual
SDN isolation.

**Pattern C — Embedded HTTP ingestion.** Also implements `PluggableModule` and loads the same way,
but instead of subscribing to events, it starts its own embedded HTTP server
(`com.sun.net.httpserver.HttpServer`) and receives data via HTTP POST, then calls
`api.publishEvent(...)` itself. Examples: `SuricataHttpModule.java` (port 8090),
`ZeekHttpModule.java` (port 8091). The `compile_suricata.ps1` / `compile_zeek.ps1` / `compile_odl.ps1`
scripts in `user-defined-modules/` build *this* pattern's modules into standalone JARs for the
registry to load — they are unrelated to Pattern A modules, which build via the Maven reactor
(`mvn -pl user-defined-modules compile`) like everything else in that directory.

**Why B/C are higher-risk.** Both are loaded through two hardcoded chokepoints in
`ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java`:

1. `initializeModules()` — a fixed list of module class names. Adding a fifth embedded module means
   editing this method in a file you didn't write.
2. `dispatch()` / `mapMessageTypeToEventType()` — an if/else chain deciding how to deserialize each
   incoming command type. A new embedded module needing a new event type needs a new branch here.

This is documented (`SDK_USABILITY_AUDIT.md`, Part 3.5) as a known, deliberately-unfixed
architectural gap — not something this skill should paper over by quietly editing
`SdkModuleHost.java` on the user's behalf. If a module genuinely needs the embedded pattern (because
it must *react* to system commands, not just produce alerts), that's a deliberate, explicit choice
the user should make with the tradeoff stated plainly, and any edit to `SdkModuleHost.java` should
be small, reviewed, and clearly called out — that file's dispatch logic is what
`OpenDaylightModule`'s live SDN mitigation path depends on.

## Gotchas worth knowing before writing a new module or its workflow YAML

These are documented findings from `SDK_USABILITY_AUDIT.md` — real bugs/quirks discovered building
prior modules, not hypothetical concerns:

- **`module.id` must exactly equal the `.properties` filename minus its extension.** The web
  dashboard correlates database records to config files by this value. A mismatch doesn't crash
  anything — the module runs fine — it just silently breaks dashboard correlation.
- **Normalize severity to lowercase `critical`/`high`/`medium`/`low` inside the module**, not just
  "any casing of the right word." `WorkflowMatcher`'s severity comparison historically had a
  case-sensitivity asymmetry between its `==` and `!=` branches; normalizing at the source is the
  same safe approach every existing module already takes, regardless of whether that asymmetry is
  currently patched.
- **There is no dedicated `category` condition branch in workflow YAML matching.** A bare condition
  on `trigger.payload.category` alone can silently match everything. Use `alert_type` (which does
  have a dedicated branch) for category-style conditions instead, the way
  `maltrail_ransomware_isolate.yml` uses `alert_type contains 'ransomware'`.
- **`threat_score` conditions only support the `>=` operator.** `>` or `==` on `threat_score`
  silently become no-ops in the workflow condition scanner. If you're writing a workflow trigger
  around the new module's `threat_score`, use `>=`.
- **`WorkflowLoader` scans its workflows directory non-recursively.** A workflow YAML file placed in
  a subdirectory (even one that looks like an established convention) is silently never loaded — no
  error, just zero effect. Keep new workflow files in the flat directory `WorkflowLoader` is
  actually pointed at.
- **Every module's `loadConfig()` defaults every key in code.** A missing or partially-filled
  `.properties` file should never prevent the module from starting — it should degrade to sensible
  built-in defaults. Keep this property when writing a new module's `loadConfig()`.

## Where to look for a worked example

`user-defined-modules/MALTRAIL_MODULE_README.md` and `SURICATA_MODULE_README.md` are full worked
examples of a Pattern A module's config, build, run, and test steps, including the actual message
JSON at each stage. `MALTRAIL_DEVELOPER_USABILITY_LOG.md` documents real build-time numbers and doc
gaps discovered while building that module — worth a skim if you want a sense of what tends to trip
people up.
