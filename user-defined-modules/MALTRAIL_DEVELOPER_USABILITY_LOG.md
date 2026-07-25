# Maltrail Integration - Developer Usability Log

**Purpose:** objective numbers for developer effort building the Maltrail integration (Task 2)
and authoring its trigger workflow (Task 3.2), per `coding_agent_execution_brief.md` Task 3.

## Important disclaimer

**This log reflects AI-assisted (Claude Code) authoring time, not independent human developer
time.** The agent had already read the full SDK/WorkflowEngine source (`SuricataModule.java`,
`WorkflowMatcher.java`, `WorkflowLoader.java`, etc.) via prior research passes in the same
session before the timed work below began, which materially shortens elapsed time compared to
a human encountering this codebase cold. Treat these numbers as a data point on "how long does
this take once the pattern is understood," not as a substitute for a human-subject usability
study. If citing this in the thesis, caveat it explicitly rather than presenting it as
equivalent to unassisted human developer time.

---

## Task 2: MaltrailModule build timeline

- **Start:** 2026-07-25T03:35:58Z
- **End (first verified success):** 2026-07-25T03:42:43Z
- **Elapsed:** ~6 minutes 45 seconds

"First successful X" caveat: no live RabbitMQ broker was available in this environment (confirmed
via a TCP probe to `localhost:5672` before starting), so "first successful `workflow_queue`
publish" could not be produced. The closest available proxy for "success" used here is: (1) the
module compiles clean via `mvn -pl user-defined-modules compile`, and (2) an offline check
confirmed the JSON-building/severity-mapping/categorization logic runs correctly (later formalized
as JUnit tests in Task 4). A live-broker-confirmed publish still needs to happen in an environment
where RabbitMQ is actually running - see `MALTRAIL_MODULE_README.md`'s Testing section.

### Doc gaps found while building

- **`WORKFLOW_YML_GUIDE.md`, referenced by `coding_agent_execution_brief.md` Task 3.2 as the
  reference doc for workflow authoring, does not exist anywhere in the repository.** The actual
  (undocumented-as-such) schema reference is `WorkflowEngine/WORKFLOW_ENGINE_COMPLETE.md`,
  specifically its "Phase 4: YAML Workflow Structure" section. Had to search the repo and fall
  back to this file.
- **`SURICATA_MODULE_README.md`'s example alert JSON payload (lines 165-181) uses stale
  camelCase field names** (`alertId`, `sourceIp`, `signatureId`, etc.) that do not match the
  actual snake_case `@SerializedName` wire format Gson really emits from `SuricataAlertData.java`
  (`alert_id`, `source_ip`, `signature_id`, etc.). Had to read `SuricataAlertData.java` source
  directly to get the real wire format right, rather than trusting the README example -
  `MALTRAIL_MODULE_README.md`'s equivalent example was written from source instead, and this
  drift is called out explicitly in the new README.
- **No documented contract for `WorkflowMatcher`'s severity-comparison case-sensitivity.** The
  `==` branch is case-sensitive (`String.equals()`) while the `!=` branch is case-insensitive
  (`equalsIgnoreCase()`) - an asymmetry visible only by reading `WorkflowMatcher.java:163-180`
  directly. This directly affects `MaltrailModule.mapSeverity()`'s design: severities must be
  normalized to exact lowercase strings, not just "any casing of the right word."
- **No dedicated `category` condition branch.** A bare condition on `trigger.payload.category`
  alone is silently ignored by every workflow (`evaluateCondition` has no dedicated
  `category` branch) - a condition like `trigger.payload.category == 'ransomware'` would match
  everything. This is not documented anywhere and was only found by reading
  `WorkflowMatcher.java` line-by-line. `maltrail_ransomware_isolate.yml` uses
  `alert_type contains 'ransomware'` instead, which does have a dedicated branch and also checks
  `category`/`note_type` as fallbacks.
- **No documented severity/threat-score numeric contract.** `threat_score` conditions only
  recognize the `>=` operator (hardcoded substring match in `extractNumberFromCondition`) -
  `>` or `==` on `threat_score` silently become no-ops. Not used in the Maltrail workflow, but
  worth flagging as a footgun for future workflow authors.

### Final LOC counts

| File | Lines |
|---|---|
| `MaltrailModule.java` | 624 |
| `MaltrailAlertData.java` | 212 |
| `maltrail-module.properties` | 27 |
| **Total (module + payload class + config)** | **863** |

(`MALTRAIL_MODULE_README.md` and `scripts/send_test_maltrail_event.py` are documentation/tooling,
not counted in the integration's own LOC.)

### File-change footprint (Task 2.4)

**New files only** - confirmed via `git status`:
- `user-defined-modules/src/main/java/com/nis1/thesis/udm/MaltrailModule.java`
- `user-defined-modules/src/main/java/com/nis1/thesis/udm/MaltrailAlertData.java`
- `user-defined-modules/config/maltrail-module.properties`
- `user-defined-modules/MALTRAIL_MODULE_README.md`
- `WorkflowEngine/workflows/ransomware/maltrail_ransomware_isolate.yml`
- `scripts/send_test_maltrail_event.py`

**Zero changes** to `CoreSystemApi.java`, `WorkflowMatcher.java`, any `WorkflowEngine` source
file, or `OpenDaylightModule.java` were made as part of this integration. (Note: `CoreSystemApi`,
`WorkflowExecutor`, `WorkflowQueueListener`, and `WorkflowMatcher` do show as modified in
`git status` at the time of writing - those changes are from the separate Task 0/Task 1 work
earlier in this session, not from the Maltrail integration. No further edits were made to any of
them while building Maltrail support.)

---

## Task 3.2: Workflow-YAML authoring timing

**Task:** given a plain-English trigger ("isolate host if a Maltrail high-severity
ransomware-category event occurs"), author the YAML and measure time-to-first-successful-match.

- **Start:** 2026-07-25T03:38:41Z
- **End (first successful match confirmed):** 2026-07-25T03:39:33Z
- **Elapsed:** ~52 seconds
- **Final line count:** 21 lines (`maltrail_ransomware_isolate.yml`)

**Caveat on the elapsed time:** this was measured immediately after the schema-research pass
above (which had already fully resolved the `WORKFLOW_YML_GUIDE.md`-doesn't-exist gap and the
`category`-branch gap), so the 52 seconds reflects writing-with-full-context, not
discovery-plus-writing from a cold start. A first-time author hitting the missing-guide and
missing-category-branch gaps noted above would need meaningfully longer.

**Verification method used:** rather than requiring a live broker, wrote a small throwaway Java
program (`WorkflowEngine/MaltrailWorkflowMatchCheck.java`, deleted after use - not part of the
build) that calls `WorkflowLoader.loadWorkflows()` and `WorkflowMatcher.findMatchingWorkflows()`
directly against a sample Maltrail-shaped alert JSON. Confirmed output:
```
Loaded 66 workflows.
Maltrail workflow present after load: true
Matched workflow count: 2
  MATCHED: Maltrail High-Severity Ransomware Trail -> SDN Isolation
  MATCHED: ODL Ransomware Detection & Prevention Workflow
RESULT: PASS - Maltrail workflow matched
```
(The second match, an existing general ransomware-category workflow, is expected and unrelated
to the new Maltrail-specific one - both target the same class of alert independently.)

---

## Task 3.3: SDK-free comparison (raw RabbitMQ, no PluggableModule/ModuleHelper)

**Not attempted.** Marked optional in the brief ("if time allows") and out of the time-box for
this session. If wanted later, the fair comparison point would be: reimplement a UDP-to-
`workflow_queue` bridge using only `com.rabbitmq.client` directly (no `ModuleHelper`, no
`PluggableModule`) and compare LOC/time against the `MaltrailModule.java` numbers above - note
that `MaltrailModule` itself already uses raw RabbitMQ (`ConnectionFactory`/`Channel`) rather
than the embedded SDK path, since it's a standalone process like `SuricataModule`, so the more
meaningful "with vs without SDK" comparison for this repo would actually be the standalone
UDM pattern (used here) vs. the embedded `PluggableModule`/`SdkModuleHost` pattern (used by
`OpenDaylightModule`), not "raw sockets vs. SDK" in the way the brief phrases it. Worth
clarifying with the thesis advisor which comparison is actually intended before attempting it.
