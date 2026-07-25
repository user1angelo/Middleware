# Coding Agent Execution Brief
### Use alongside `SDK_CODEBASE_ANSWERS.md` (repo state as of 2026-07-23)

Read `SDK_CODEBASE_ANSWERS.md` first — every task below references specific findings and line numbers from that document. Do not re-derive facts about the codebase from memory; if something here conflicts with what you find in the live repo, the live repo wins and should be flagged back.

Work through tasks in numbered order. Each task has a **Goal**, **Do**, and **Done when** so progress is checkable.

---

## Task 0 — Fix Known Inconsistencies (do first, blocks everything else)

**Goal:** remove contradictions between docs/schema and runtime code before building anything on top of them.

0.1 **Schema/table mismatch.** `ThreatContextStore/schema.sql` creates table `wazuh_alerts`; `WazuhAlertDao` inserts into `alerts`. Confirm which table the live Postgres instance (`192.168.159.70:5432/NIS1`) actually has. Reconcile `schema.sql` to match reality (rename in the DAO or the schema, whichever matches the running DB) so a fresh deploy from the schema file actually works.

0.2 **Javadoc/runtime mismatch.** `CoreSystemApi.java` Javadoc (lines ~24–29) describes topic-exchange routing. Runtime (`WorkflowQueueListener.java`, `AlertBroadcastListener.java`, `WorkflowExecutor.java`) uses direct named queues via `basicPublish("", queueName, ...)` with no `exchangeDeclare`/`queueBind`. Rewrite the Javadoc to describe the actual named-queue implementation.

0.3 **Dead/broken timing log.** `WorkflowQueueListener` (lines ~141–159) prints a "total containment time" that subtracts two consecutive `now()` calls and is not measuring real elapsed time. Remove this log line — it will be replaced by Task 1's real instrumentation.

0.4 **README test claim.** `README.md` (lines ~88–93) states each module has tests. No `src/test` JUnit classes exist for `Event<T>`/payload serialization. Either update the README to reflect reality now, or leave a `// TODO: see Task 4` note and fix the claim once Task 4 lands.

**Done when:** schema matches DB, Javadoc matches runtime behavior, dead timing code removed, README claim is either true or flagged.

---

## Task 1 — SDK-Only Stage Timing Instrumentation

**Goal:** measure how long the SDK's own code takes, separated from Suricata/ODL time, per alert.

1.1 Create `com.nis1.thesis.sdk.telemetry.StageTimer` (or equivalent package) — a small utility, no new dependency (repo has no Micrometer/OpenTelemetry; don't add one). It should:
- Expose `start(String traceId, String stage)` / `stop(String traceId, String stage)`.
- On `stop`, append one line to a per-run CSV: `traceId,stage,startEpochMs,endEpochMs,durationMs`.
- Accept a configurable output path (default `./benchmark_output/benchmark_run_<timestamp>.csv`).
- Use the alert's own `alert_id` (already present per `SuricataAlertData.java`) as `traceId` so all stages for one alert join cleanly later.

1.2 Instrument these five boundaries exactly (per `SDK_CODEBASE_ANSWERS.md` item 25):

| # | Stage name | File | Location |
|---|---|---|---|
| 1 | `consume_deserialize` | `WorkflowQueueListener.java` | start at top of `DeliverCallback`; stop immediately after `new JSONObject(message)` (~line 67) |
| 2 | `workflow_load` | `WorkflowQueueListener.java` | around `workflowLoader.loadWorkflows()` (~lines 97–100) |
| 3 | `policy_match` | `WorkflowQueueListener.java` | around `workflowMatcher.findMatchingWorkflows(...)` (~lines 121–123) |
| 4 | `command_dispatch` | `WorkflowExecutor.java` | around `channel.basicPublish` in `executeStep()` (~lines 160–165) |
| 5 | `registry_route_dispatch` | `CommandRoutingListener.java` | around `routeCommand()` and `sdkModuleHost.dispatch()` (~lines 67–80, 160–165) |

1.3 Leave existing tool-side telemetry alone but note its location for later joining: Suricata generation/receipt timestamps (`SuricataModule.java:411-438`), ODL benchmark logging (`OpenDaylightModule.java:580-611`).

1.4 Add a small run script (Python or Java, agent's choice) that, given a benchmark CSV, computes **mean, median, p95, max** per stage and writes a summary table.

**Done when:** running one alert end-to-end produces a CSV with 5 SDK-stage rows plus existing tool-side timestamps, and the summary script produces per-stage stats from ≥30 runs.

---

## Task 2 — Maltrail Integration (primary flexibility/extensibility evidence)

**Goal:** integrate Maltrail as a second, independent NIDS data source with **zero changes to `CoreSystemApi`, `WorkflowMatcher`, `WorkflowEngine`, or `OpenDaylightModule`** — the absence of core changes IS the extensibility proof, so track and report exactly which files were touched.

### 2.1 Confirmed Maltrail event schema (use this, don't guess)
Maltrail's sensor can emit JSON events over UDP to a Logstash-style listener when `LOGSTASH_SERVER <host>:<port>` is set in `maltrail.conf`. Each event is a JSON object with this field set (from Maltrail's `core/log.py`):
```json
{
  "timestamp": <unix_sec>,
  "sensor": "<hostname>",
  "severity": "<string>",
  "src_ip": "...",
  "src_port": <int>,
  "dst_ip": "...",
  "dst_port": <int>,
  "proto": "tcp|udp|...",
  "type": "<trail type, e.g. ip|dns|url>",
  "trail": "<matched indicator>",
  "info": "<threat description, e.g. 'ransomware'>",
  "reference": "<source feed / '(static)'>"
}
```
This is the integration point — build a small UDP listener, not a file-tail (Maltrail's local log format is a different, less structured, space-delimited text format — avoid parsing that).

### 2.2 Module shape — mirror `SuricataModule.java`
Create `MaltrailModule` in `user-defined-modules` as a **standalone process** (same pattern as `SuricataModule`, not the embedded `SdkModuleHost` pattern — matches Maltrail's push-based UDP delivery rather than a file to tail):
1. Open a UDP socket on the port configured for `LOGSTASH_SERVER` to point at.
2. On each datagram, parse JSON into a `MaltrailEvent` POJO matching §2.1's schema (use Gson, consistent with `SuricataModule`'s existing dependency).
3. Map to a `MaltrailAlertData` payload class mirroring `SuricataAlertData.java`'s shape and field names where semantically equivalent, so `WorkflowMatcher`'s existing condition evaluation (severity, category/type, signature-equivalent) works unmodified:
   - `signature` ← Maltrail `info` (e.g., "ransomware", "malware feed: X")
   - `severity` ← Maltrail `severity`, normalized to match whatever scale `WorkflowMatcher` already expects from Suricata (check `WorkflowMatcher.java:89-236` for the exact severity comparison logic before mapping — match it, don't invent a new scale)
   - `category`/`event_type` ← derived from Maltrail `type` (ip/dns/url) — pick a consistent mapping and document it
   - source/dest IP and ports ← direct passthrough
   - `alert_id` ← generate one (Maltrail events don't have a native ID) — hash of timestamp+src+dst+trail is sufficient
4. Publish to `workflow_queue` using the same `basicPublish("", "workflow_queue", ...)` call shape as `SuricataModule.publishSuricataAlert()` — same queue, same downstream path, no changes needed to anything past ingestion.
5. Config: add `maltrail.udp_port` (and RabbitMQ connection reuse) to a new `MaltrailModule` config properties file, following the pattern of existing module configs.

### 2.3 End-to-end validation
- Configure a real or simulated Maltrail sensor (or a simple script that sends a UDP packet matching §2.1's schema if standing up full Maltrail isn't practical in the test window) to emit at least one event matching an existing threat-indicator trail (e.g., a known ransomware C2 domain in Maltrail's trail lists).
- Confirm: event received by `MaltrailModule` → published to `workflow_queue` → matched by an existing or new workflow YAML → mitigation command dispatched to ODL → isolation confirmed.
- Capture this as a full trace (reuse Task 1's `StageTimer` on this run too — gives you a same-instrumentation comparison between Suricata-sourced and Maltrail-sourced alerts through the identical downstream pipeline).

### 2.4 Track and report for the thesis
- **File change footprint**: list every file created vs. modified. Expected: only new files (`MaltrailModule.java`, `MaltrailAlertData.java`, config, one workflow YAML if a new one is needed) — flag immediately if anything in `WorkflowEngine`, `nis-thesis-sdk`, or `OpenDaylightModule` had to change, since that would weaken the "no core changes" claim and needs to be reported honestly either way.
- **Lines of code** for the new module (rough count is fine).
- **Time to working integration** (log start/end while building it — feeds directly into the developer-usability evidence, Task 3).

**Done when:** a Maltrail-format UDP event flows end-to-end to a dispatched mitigation command, with zero modifications to `CoreSystemApi`/`WorkflowEngine`/`OpenDaylightModule` confirmed and documented.

---

## Task 3 — Developer Usability Logging (supports the "how easy" claims)

**Goal:** produce objective numbers for developer effort, not just a built artifact.

3.1 While building Task 2, keep a simple running log (a markdown or CSV file is fine) of:
- Timestamp when module-build task started and when the first successful `workflow_queue` publish was confirmed.
- Every time the docs (`user-defined-modules/README.md`, `SDK_Detailed_Context.md`) were insufficient and source code had to be read directly to figure out the contract — note what was missing from the docs.
- Final LOC count for `MaltrailModule.java` + `MaltrailAlertData.java` + config.

3.2 Separately, time a workflow-YAML authoring task: given only `WORKFLOW_YML_GUIDE.md` and a plain-English trigger condition (e.g., "isolate host if a Maltrail high-severity ransomware-category event occurs"), write the YAML, and log time-to-first-successful-match (checkable via `WorkflowMatcher` debug output) and final line count.

3.3 If time allows, repeat 3.1's task without the SDK (raw RabbitMQ connection + manual JSON publish, no `PluggableModule`/`ModuleHelper`) for a direct LOC/time comparison — optional but strengthens the "flexibility advantage" claim.

**Done when:** you have concrete numbers (not estimates) for module-build time/LOC and workflow-authoring time/LOC.

---

## Task 4 — Compatibility Unit Tests

**Goal:** back the compatibility-testing claim with real, runnable tests (currently none exist per `SDK_CODEBASE_ANSWERS.md` item 30).

4.1 Add JUnit tests (`src/test/java`) for:
- Round-trip serialize/deserialize of `Event<T>`, `NidsAlertData`, `SuricataAlertData`, and the new `MaltrailAlertData`.
- Documented drop behavior: non-`alert` EVE records ignored; missing `src_ip`/`dest_ip` dropped; malformed JSON dropped (per item 29) — assert on actual return values/log output, not just "doesn't throw."
- Equivalent malformed-input handling for the new `MaltrailModule` (malformed UDP payload, missing required fields).

**Done when:** `mvn test` runs and passes a suite covering both Suricata and Maltrail payload paths.

---

## Deliverables Checklist (what should exist when this brief is complete)
- [ ] Task 0 fixes committed
- [ ] `StageTimer` utility + 5 instrumentation points + ≥30-run benchmark CSVs + summary stats
- [ ] `MaltrailModule` + `MaltrailAlertData` + config + at least one successful end-to-end trace log
- [ ] File-change footprint list for the Maltrail integration (proving no core changes)
- [ ] Developer usability log (module-build time/LOC, workflow-authoring time/LOC)
- [ ] JUnit compatibility test suite covering Suricata + Maltrail payloads
- [ ] Updated Javadoc on `CoreSystemApi` (Task 0.2) ready for appendix generation via `mvn -pl nis-thesis-sdk javadoc:javadoc`
