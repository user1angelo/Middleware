# SDK Usability & Effectiveness Audit

**Scope.** This document covers `nis-thesis-sdk` (the formal, embedded SDK surface:
`CoreSystemApi`, `PluggableModule`, `Event<T>`, `ModuleHelper`, and the payload/data classes)
**and** the informal standalone-module convention that most modules in this repository actually
use (`SuricataModule.java`, `MaltrailModule.java`, and the file-tail/UDP pattern they share).
Both are audited together because, in practice, a developer extending this system chooses
between them — evaluating only the formal interface would miss half of how the system is
actually used.

**How to read this document.** Part 1 explains how the SDK works, from scratch, with real code.
Part 2 gives an explicit verdict on how effective it is. Part 3 applies the Cognitive Dimensions
framework, dimension by dimension, with file/line evidence. Part 4 checks the SDK against a
practical modern-API ergonomics checklist. Every claim of fact is backed by a specific file and
line reference; every judgment is stated as a judgment, not disguised as one.

**Revision note.** This is a two-pass document. The first pass scored the SDK at 1 PASS / 5
MIXED / 6 FAIL across the 12 Cognitive Dimensions. A second, targeted round of work then fixed
six of those issues with real, verified code changes (not just re-wording) — Part 3's summary
table and each affected dimension now show both the original verdict and the fix. Three
dimensions were left MIXED/FAIL by **explicit, documented decision**, not oversight: fixing them
requires replacing `SdkModuleHost`'s hardcoded embedded-module list and hardcoded dispatch logic,
which was judged too close to `OpenDaylightModule`'s live SDN mitigation routing to risk without a
dedicated regression-testing pass of its own.

---

## Methodology and Citations

This audit's evaluative framework is the **Cognitive Dimensions of Notations**, applied to API
design. The framework originates in:

> Green, T. R. G., & Petre, M. (1996). Usability analysis of visual programming environments: A
> 'cognitive dimensions' framework. *Journal of Visual Languages & Computing*, 7(2), 131–174.
> https://doi.org/10.1006/jvlc.1996.0009

Green & Petre's original framework was designed for visual programming notations generally, not
APIs specifically. The 12-dimension adaptation this audit actually follows — the one that
directly names dimensions like "API Elaboration" and "API Viscosity" — comes from Microsoft's
applied version:

> Clarke, S. (2005). *Describing and Measuring API Usability with the Cognitive Dimensions.*
> Microsoft Corporation.

This is cited in full (author, title, publisher, year) per standard citation practice for a
technical/position paper without an assigned DOI — the absence of a DOI does not disqualify a
source from being a legitimate, citable reference; DOIs are a retrieval convenience introduced
long after much foundational HCI/SE literature was published, and citation validity rests on
verifiability (author, venue, year, retrievable text), all of which this paper satisfies.

A third, independently peer-reviewed source applies this same 12-dimension framework specifically
to API usability studies and is offered here as a second academic anchor:

> Grill, T., Polacek, O., & Tscheligi, M. (2012). Methods towards API Usability: A Structural
> Analysis of Usability Problem Categories. In *Human-Centered Software Engineering* (pp.
> 164–180). Springer. https://doi.org/10.1007/978-3-642-34347-6_10

The 12 dimensions used throughout Part 3, per Clarke (2005):
Abstraction Level, Learning Style, Working Framework, Work-Step Unit, Progressive Evaluation,
Premature Commitment, Penetrability, API Elaboration, API Viscosity, Consistency, Role
Expressiveness, Domain Correspondence.

---

# Part 1 — How the SDK Actually Works

## 1.1 Three integration patterns exist, not one

The SDK's own interface (`PluggableModule`) implies a single, uniform way to extend the system.
In practice, this codebase contains **three genuinely different integration patterns**, and a
new module author must pick one without much guidance on which:

**Pattern A — Standalone process.** A plain Java class with its own `main()`, its own RabbitMQ
`Connection`/`Channel`, its own registration/heartbeat/command-listener logic, and its own
ingestion mechanism (file-tail or UDP listener). Does **not** implement `PluggableModule` at all.
Examples: `SuricataModule.java`, `MaltrailModule.java`
(`user-defined-modules/src/main/java/com/nis1/thesis/udm/`). This is the pattern this session
used twice (Maltrail, then Fail2ban/Sysmon) specifically because it requires zero changes to any
shared/core file — a new module is just a new file plus a new config file.

**Pattern B — Embedded event-subscriber.** Implements `PluggableModule`, is loaded in-process by
`SdkModuleHost` from a hardcoded class list, and does its work purely by subscribing to event
types via `CoreSystemApi.subscribeToEvent(...)` and reacting when `SdkModuleHost.dispatch(...)`
calls it. Example: `OpenDaylightModule.java`
(`initialize()` at lines 62-90 calls `api.subscribeToEvent("INITIATE_MITIGATION",
this::onMitigationCommand)` and four other subscriptions).

**Pattern C — Embedded HTTP ingestion.** Also implements `PluggableModule` and is loaded the same
way as Pattern B, but instead of subscribing to events, it starts its **own embedded HTTP
server** (`com.sun.net.httpserver.HttpServer`) on a dedicated port and receives data via HTTP
POST from the external tool, then calls `api.publishEvent(...)` itself. Examples:
`SuricataHttpModule.java` (port 8090, path `/suricata/alerts`), `ZeekHttpModule.java` (port 8091,
path `/zeek/notices`).

All three are loaded via the identical `PluggableModule.initialize(CoreSystemApi)` contract, yet
they behave completely differently at runtime — one runs a background server, one is purely
reactive, and one isn't even part of the interface. This split is a recurring theme throughout
Part 3 (see Consistency, Abstraction Level, API Viscosity).

## 1.2 `CoreSystemApi`

`nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/CoreSystemApi.java` — a two-method interface:

```java
void publishEvent(Event<?> event);
void subscribeToEvent(String eventType, Consumer<Event<?>> listener);
```

The only concrete implementation is `SdkModuleHost.RealCoreSystemApi`
(`ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java`).
Behavior actually implemented (as of this document, before Part A's planned extension):
- `publishEvent`: only forwards events whose `type` starts with the literal prefix `"alerts."` to
  a single fixed RabbitMQ queue via the default exchange (`basicPublish("", queueName, ...)`).
  Anything else is silently accepted and dropped — no error, no log beyond a console line.
- `subscribeToEvent`: registers into a **shared** `Map<String, List<Consumer<Event<?>>>>` keyed by
  exact event-type string, plus a single-level `"prefix.*"` wildcard convention. This map is
  shared across every embedded module in the same JVM — a detail with real consequences (see
  §1.7 and the Consistency dimension).

## 1.3 `PluggableModule`

`nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/PluggableModule.java` — a three-method contract:
```java
String getName();
void initialize(CoreSystemApi api);
void shutdown();
```
That's the entire formal interface. It says nothing about *how* a module should acquire input
(subscribe to events? run a server? none of the SDK's business) — which is exactly why Patterns B
and C diverge so much while both satisfying this interface.

## 1.4 `Event<T>`

`nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/Event.java` — an immutable, generic envelope:
```java
public final class Event<T> {
    private final String id;
    private final Instant timestamp;
    private final String type;
    private final T data;
    public Event(String id, Instant timestamp, String type, T data) { ... } // null-checks all 4
    public static <T> Event<T> of(String type, T data) { ... } // auto id + timestamp
}
```
No `equals()`/`hashCode()`/`toString()`. No Gson annotations. This session's JUnit tests
(`EventTest.java`) found and documented a real gap here: a plain `new Gson()` — the exact
configuration `SdkModuleHost` itself uses — **cannot serialize `Event`'s `Instant timestamp`
field at all** on this JDK (throws `JsonIOException`); the module declares a
`gson-javatime-serialisers` dependency specifically to solve this, but nothing in the source tree
actually wires it into a `GsonBuilder`. In practice this has never surfaced as a runtime bug only
because `SdkModuleHost.publishEvent` happens to serialize `event.getData()` alone, never the
whole `Event` object.

## 1.5 `ModuleHelper` and the payload/data classes

`ModuleHelper` (`nis-thesis-sdk/.../ModuleHelper.java`) wraps `CoreSystemApi` with six
convenience methods that hide `Event.of(...)` construction:
`publishIpReputation`, `publishHostAlert`, `publishNidsAlert`, `publishMitigationCommand`,
`requestIpEnrichment`, and two overloaded `log(...)` helpers. Each wraps one payload class:

| Payload class | Fields | Notes |
|---|---|---|
| `HostAlertData` | `sourceIp`, `description`, `severity` (all `String`) | Minimal - no timestamp, no hostname field |
| `IpReputationData` | `ipAddress`, `isMalicious` (`boolean`), `source`, `category`, `confidenceScore` (`Integer`) | 3 constructors |
| `NidsAlertData` | `sourceIp`, `destinationIp`, `sourcePort`/`destinationPort` (`Integer`), `protocol`, `signature`, `signatureSeverity`, `category`, `hostTag` | No `@SerializedName` - JSON keys are raw camelCase |
| `EnrichmentRequestData` | `ipAddress`, `domain`, `fileHash`, `enrichmentType`, `requestId` | See finding below |
| `MitigationCommandData` | `targetHost`, `action` (`MitigationAction` enum), `justification`, `workflowInstanceId`, `priority` (`Integer`), `additionalParameters` (`String`) | `additionalParameters` is raw untyped text, doc comment says "JSON or key-value format" |
| `MitigationAction` (enum) | `QUARANTINE, BLOCK_IP, RATE_LIMIT, REDIRECT_TRAFFIC, ISOLATE_VLAN, ALERT_ONLY, DISABLE_USER, KILL_PROCESS` | Closed set - a new action requires an SDK change, not a plugin-level one |

**Concrete finding:** `EnrichmentRequestData` has two constructors that are call-site ambiguous —
`new EnrichmentRequestData(String ipAddress)` (sets `ipAddress` + defaults `enrichmentType` to
`"IP_REPUTATION"`) and `new EnrichmentRequestData(String enrichmentType, String requestId)` (sets
neither `ipAddress` nor uses the first arg as an IP at all). A reader skimming call sites for
`new EnrichmentRequestData("something", "something-else")` cannot tell which fields end up set
without checking the parameter *names*, not just arity — a real Role Expressiveness problem (see
Part 3, dimension 11).

## 1.6 The actual end-to-end message flow

Confirmed by direct source inspection in an earlier session
(`SDK_CODEBASE_ANSWERS.md`, item 19), reproduced here for a self-contained read:

1. `SuricataModule.startEveJsonMonitoring()` (file-tail) or `MaltrailModule`'s UDP listener parses
   raw input and calls `channel.basicPublish("", "workflow_queue", ...)`.
2. `AlertBroadcastListener` consumes `workflow_queue`, broadcasts to `alerts_queue` +
   `workflow_queue`.
3. `WorkflowQueueListener` consumes `workflow_queue`, calls `WorkflowLoader.loadWorkflows()`
   (re-parses every `.yml` file **on every single alert** — no caching, no file-watch), then
   `WorkflowMatcher.findMatchingWorkflows()`, then `WorkflowExecutor.executeWorkflow()`.
4. `WorkflowExecutor.executeStep()` builds a `workflow_command` JSON and publishes it to
   `workflow_response_queue`.
5. `CommandRoutingListener` consumes `workflow_response_queue`, looks up the target module in
   `ModuleRegistry` (by explicit `target_module` or by `findModuleByCapability(...)`), and either
   calls `sdkModuleHost.dispatch(json)` (if `metadata.runtime == "embedded"`) or publishes to that
   module's own command queue (standalone modules).
6. For an embedded module, `SdkModuleHost.dispatch()` re-parses the command JSON into an
   `Event<?>` and calls `api.dispatchLocal(event)`, which does an exact-match-then-wildcard
   lookup against the shared subscriber map and invokes the matching `Consumer<Event<?>>`.

## 1.7 Where the documented design and the actual behavior disagree

- `CoreSystemApi`'s Javadoc, before this session's Task 0.2 fix, described AMQP topic-exchange
  routing with wildcard patterns. The actual implementation has never used a topic exchange —
  every publish is a direct named queue via the default exchange, and "wildcard" subscription
  support is a single hand-rolled `"prefix.*"` string check, not real AMQP topic algebra. Fixed
  in this session, noted here because the gap itself is a usability data point: the interface's
  own documentation overstated its actual flexibility for years.
- The embedded-module list in `SdkModuleHost.initializeModules()` is a **hardcoded** 3-line list
  (`OpenDaylightModule`, `SuricataHttpModule`, `ZeekHttpModule`). A new embedded module cannot be
  added by dropping a JAR into a modules directory — despite `ModuleRegistry` separately scanning
  a `modules.root` directory for JARs, that scan only creates placeholder database records; it
  explicitly does not load or start anything (`ModuleRegistry.java`, documented in
  `SDK_CODEBASE_ANSWERS.md` item 33). "Pluggable" in `PluggableModule`'s name is aspirational for
  this pattern, not literal.
- `registerSdkModuleWithRegistry` registered a module's `capabilities` as
  `api.getCapabilities()` — the **entire shared listener map's keyset** at that moment, not the
  registering module's own subscriptions. Because embedded modules initialize sequentially from
  one list, each later module's registered capabilities silently included every earlier module's
  event types too. Combined with `ModuleRegistry.findModuleByCapability`'s first-match-wins
  lookup over an *unordered* `ConcurrentHashMap`, this was a latent bug: adding a fourth embedded
  module risked `findModuleByCapability("INITIATE_MITIGATION")` non-deterministically returning
  the wrong module. **Fixed as part of this round of work** (`SdkModuleHost.initializeSingleModule`
  now snapshots `api.getCapabilities()` immediately before and after `module.initialize(api)` and
  registers only the difference) — kept here as a documented finding because the bug's existence,
  not just its fix, is itself Consistency/Premature-Commitment evidence.
- **`WorkflowLoader.loadWorkflows(directory)` uses `java.io.File.listFiles(...)`
  (`WorkflowLoader.java:35`) — non-recursive.** `WorkflowEngine/workflows/ransomware/sdnworkflows/`
  is a real subdirectory containing 17 real, well-formed workflow YAML files, complete with its
  own `README.md` — and **none of them are ever loaded by the running system**, because the
  loader only lists the immediate contents of whatever directory it's pointed at, never
  descending into subdirectories. This was discovered directly during this round of work: two
  new workflow files were initially placed in a new `notifications/` subdirectory (mirroring what
  looked like an established organizational convention) and silently failed to match anything —
  no error, no warning, just zero effect — until moved to the flat directory. This is a textbook
  Penetrability/Role-Expressiveness failure: the directory's own existence, naming, and README
  actively signal "this is how workflows are organized here," and that signal is false for the
  engine's actual behavior.

---

# Part 2 — Effectiveness & Usefulness Assessment

## What the SDK makes genuinely easy

- **Adding a new standalone data source requires zero changes to any shared file.** Demonstrated
  twice now (Maltrail, and — per this round of work — Fail2ban/Sysmon): a new module is a new
  Java file, a new payload class, and a new `.properties` file. Nothing in `CoreSystemApi`,
  `WorkflowMatcher`, `WorkflowEngine`, or `ModuleRegistry` needs to change. This is the SDK's
  strongest usability result and directly supports its stated extensibility goal — *for this one
  pattern*.
- **Mirroring an existing module is low-effort and low-risk.** Because `SuricataModule` and
  `MaltrailModule` are self-contained, copy-adapt-rename is a viable, safe strategy for a new
  author — confirmed by this session's own build log (`MALTRAIL_DEVELOPER_USABILITY_LOG.md`):
  building a full new module this way took roughly 7 minutes once the pattern was understood.
- **Field-name mirroring keeps the downstream matching engine free.** Because every
  `*AlertData` class reuses `SuricataAlertData`'s field names (`severity`, `alert_type`,
  `threat_score`, etc.), `WorkflowMatcher`'s existing condition logic works unmodified across
  every new source — a real, working abstraction boundary between "how an alert arrives" and
  "how an alert is matched."
- **Heavy, consistent console logging makes runtime behavior observable without a debugger** —
  every module logs its own registration, heartbeat, and every message it handles.

## What the SDK makes hard or error-prone

- **The embedded path still requires editing two shared, global methods**
  (`initializeModules()`'s hardcoded list, and `dispatch()`'s hardcoded event-type branches) to
  add a new embedded module. This is a **deliberate, unfixed gap** — the fix (generic
  discovery/dispatch instead of hardcoded lists) was scoped out of this round of work specifically
  because it would touch the exact code path `OpenDaylightModule`'s live SDN mitigation routing
  depends on, and the risk wasn't judged worth taking without a dedicated regression pass. See
  the Work-Step Unit, API Viscosity, and Premature Commitment sections in Part 3, which remain
  MIXED/FAIL by this same explicit decision.
  ~~The cumulative-capabilities correctness bug~~ this was previously flagged as compounding the
  above has since been **fixed**: `initializeSingleModule` now snapshots capabilities
  before/after each module's `initialize()` call and registers only that module's own new
  subscriptions, removing the non-deterministic-routing risk (§1.7).
- **No documentation anywhere explains when to choose which of the three patterns** - **fixed**:
  the webapp's Patterns page now has a dedicated "Standalone Process Pattern" section laid out
  side-by-side with the embedded patterns, with an explicit "why choose this" comparison.
- **The workflow condition language is a hand-rolled string scanner, not a real expression
  parser**, and its own author's Javadoc says so (`WorkflowLoader.java`, class comment: "a
  simplified implementation... For production, consider using SnakeYAML"). This remains true -
  it's still a substring-anchored scanner, not a real parser, and unrecognized field names still
  silently match everything. What **has been fixed**: the specific case-sensitivity asymmetry
  between `==` and `!=` on `severity` (both are now `equalsIgnoreCase`), and there is now a
  supported, permanent tool (`WorkflowDryRunTool.java`) for checking a condition against a sample
  alert without reading the scanner's source first.
- ~~Boilerplate duplication~~ **fixed**: `AlertEnvelopeBuilder` (new, in `nis-thesis-sdk`) now
  builds the standard envelope; all four standalone modules were refactored to use it, verified
  by the full JUnit suite still passing unchanged (the refactor preserves exact output).

## Overall verdict

**Updated after this round of fixes.** The SDK still succeeds concretely at its narrowest,
most-exercised claim: a new standalone data-source integration can be added with zero changes to
shared code, demonstrated on three unrelated message shapes (JSON file-tail, UDP JSON, plain-text
log). Six of the twelve Cognitive Dimensions issues identified in the first pass of this audit
have now been fixed with real, verified code changes (Part 3 below has the full before/after per
dimension) — the SDK is measurably more usable than it was, not just re-graded.

What has **not** changed, by explicit, documented choice rather than oversight: the embedded
integration path (Pattern B/C) still requires editing shared global state to add a new module,
and still carries real premature-commitment cost once that path is chosen. Fixing that properly
means replacing `SdkModuleHost`'s hardcoded module list and hardcoded per-event-type `dispatch()`
branches with a generic mechanism — a change large enough, and close enough to
`OpenDaylightModule`'s live SDN mitigation routing, that it was deliberately deferred rather than
risked in this pass. The extensibility claim is real and now more broadly evidenced across more
of the API surface; the embedded path's design debt is real too, and is called out explicitly
rather than hidden.

---

# Part 3 — Cognitive Dimensions Audit

Each dimension below follows the same explicit structure: what the framework says a well-designed
API/notation should look like on that dimension (synthesized from Green & Petre, 1996 and
Clarke, 2005 — the foundational descriptions, since the 2005 position paper itself only lists the
12 names without elaborating each one), then what this SDK **actually** does, measured against
that prescription, then a blunt pass/fail verdict. Where the SDK fails, it is stated as a failure,
not softened into "an opportunity."

**Summary**

*Updated after a targeted round of fixes. 7 of 12 now PASS (was 1/12). The 3 dimensions left
MIXED/FAIL were left that way by explicit decision — see each section for why — not missed.*

| # | Dimension | Verdict |
|---|---|---|
| 1 | Abstraction Level | **PASS** *(was MIXED)* — `AlertEnvelopeBuilder` now covers the data-handling gap |
| 2 | Learning Style | **PASS** *(was MIXED)* — webapp docs now lay out all 3 patterns with a decision guide |
| 3 | Working Framework | MIXED — asymmetric, small for standalone / large for embedded (unchanged, see note) |
| 4 | Work-Step Unit | MIXED — asymmetric, excellent for standalone / poor for embedded (deliberately unfixed) |
| 5 | Progressive Evaluation | **PASS** *(was FAIL)* — `WorkflowDryRunTool.java` ships as a permanent tool |
| 6 | Premature Commitment | **FAIL** — irreversible pattern choice, worse for embedded (deliberately unfixed) |
| 7 | Penetrability | **PASS** *(was MIXED)* — webapp now queries real, live capabilities from Postgres |
| 8 | API Elaboration | **PASS** *(was FAIL)* — same `AlertEnvelopeBuilder` fix as #1 |
| 9 | API Viscosity | **FAIL** — embedded changes ripple into shared global state (deliberately unfixed) |
| 10 | Consistency | **PASS** *(was FAIL)* — severity `==`/`!=` asymmetry fixed; architectural split noted below |
| 11 | Role Expressiveness | **PASS** *(was FAIL)* — all 3 concrete instances fixed with typed replacements |
| 12 | Domain Correspondence | **PASS** — the one dimension without a real complaint (unchanged) |

**On the 3 that stayed MIXED/FAIL (#3, #4, #6, and #9's embedded-path half):** all four trace back
to the same root cause — `SdkModuleHost`'s hardcoded embedded-module list and hardcoded
per-event-type `dispatch()` branches. Fixing that root cause properly requires replacing it with
a generic discovery/dispatch mechanism, which was explicitly scoped **out** of this round because
it's the one change in this whole audit that touches code `OpenDaylightModule`'s live SDN
mitigation routing directly depends on. This was a deliberate risk/reward call — six dimensions
fixable with low, contained risk were fixed; one architectural fix with real blast radius was
left for a dedicated pass with its own regression testing, rather than bundled in here.

### 1. Abstraction Level
**What the framework prescribes:** an API's exposed abstractions should sit at a level matched to
how the programmer thinks about the task — not so primitive that every task requires reassembling
low-level parts, not so aggregate that the programmer can't compose anything new (Green & Petre,
1996; Clarke, 2005).

**What this SDK actually does:** `PluggableModule` gives a genuinely task-level abstraction for
*lifecycle* (`getName`/`initialize`/`shutdown` — three calls, done). But it gives **zero
abstraction** for the one thing every single module in this repo actually spends most of its code
on: turning raw external data into a published alert. `SuricataModule.publishSuricataAlert`,
`MaltrailModule.buildMaltrailAlertJson`, `Fail2banModule.buildFail2banAlertJson`, and
`SysmonModule.buildSysmonAlertJson` all independently hand-build an identical `JSONObject`
envelope from scratch — four separate implementations of the same idea, in a codebase that has
an SDK specifically to prevent this. `ModuleHelper` *is* a factored abstraction at the right
level, but it's reachable only from the embedded path and none of the four standalone modules
above use it.

**Verdict: PASS** *(was: MIXED, trending toward FAIL on the part that matters most)*. **Fixed:**
`AlertEnvelopeBuilder` (`nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/AlertEnvelopeBuilder.java`)
now provides exactly this abstraction — a fluent builder covering the envelope shape every
module needs (`.eventType(...).sourceModule(...).payload(...).build()`). All four standalone
modules (`SuricataModule`, `MaltrailModule`, `Fail2banModule`, `SysmonModule`) were refactored to
use it instead of hand-building the envelope, verified by the full JUnit suite passing unchanged
(the refactor is behavior-preserving, not just a new unused utility sitting next to the old
code). The lifecycle abstraction (`PluggableModule`) was already fine; the previously-missing
data-handling abstraction now exists and is actually used.

### 2. Learning Style
**What the framework prescribes:** a usable API should support incremental, exploratory learning
— a programmer should be able to learn one part, try it, and build understanding piece by piece,
rather than needing to absorb the entire system's design before writing a single line that works.

**What this SDK actually does:** Within one pattern, this genuinely holds — copying
`SuricataModule.java`, renaming, and adjusting the parser is directly demonstrated in this
session's own build log (`MALTRAIL_DEVELOPER_USABILITY_LOG.md`) as a viable, incremental,
trial-and-error path. But the prescription breaks the moment a learner needs to discover that
**three incompatible patterns exist at all** — nothing in the codebase surfaces this; it can only
be learned by independently reading `OpenDaylightModule.java`, `SuricataHttpModule.java`, and
`SuricataModule.java` end to end and noticing they don't resemble each other.

**Verdict: PASS** *(was: MIXED — good exploratory learning within a chosen pattern, a hard
discontinuity in learning that the choice of pattern exists in the first place)*. **Fixed:** the
webapp's SDK Patterns page (`webapp/frontend/src/pages/SdkPatterns.js`) now has a dedicated
"Standalone Process Pattern" section, laid out immediately alongside the three embedded patterns,
with an explicit "why choose this over the embedded pattern" comparison and a worked code
skeleton. A learner discovers that multiple patterns exist, and gets a decision guide, before
committing to copying any single example — closing the discontinuity described above.

### 3. Working Framework
**What the framework prescribes:** the amount of background/domain knowledge a programmer must
hold before an API's own operations make sense should be as small as the task allows.

**What this SDK actually does:** For the standalone path, the required background is genuinely
small — RabbitMQ connect/declare/publish, plus one envelope shape, both learnable from a single
file. For the embedded path, the required background is an order of magnitude larger and was
**not previously documented anywhere as a single coherent picture**: the shared subscriber map,
the hardcoded initialization order, the capability-registration mechanism, and
`CommandRoutingListener`'s embedded-vs-queue branching. This document (§1.7) is the first place
all four of those facts appear together — before this audit, understanding the embedded working
framework required reading four separate files and inferring the connections yourself.

**Verdict: MIXED, asymmetric by design.** Small and appropriate for the majority-used pattern;
large for the embedded one — though at least now documented in one place (this document, plus the
webapp's Learning Style fix above), which was itself part of the problem before this round of
work. The underlying asymmetry itself is **left as-is, deliberately** — see the summary table's
note on why #3/#4/#6/#9 were scoped out of this round.

### 4. Work-Step Unit
**What the framework prescribes:** the smallest unit of change a programmer can make should be
appropriately sized to the task — not forcing large, all-or-nothing commitments for small
intentions.

**What this SDK actually does:** Standalone: one new module is genuinely a self-contained unit —
zero edits anywhere else, confirmed four times over (Maltrail, Fail2ban, Sysmon, plus the
original Suricata baseline). Embedded: one new module is **not** a self-contained unit — it
necessarily edits two shared, global methods (`initializeModules()`'s hardcoded list and
`dispatch()`'s hardcoded event-type branches) in a file (`SdkModuleHost.java`) the new module's
author did not write and other modules depend on.

**Verdict: MIXED, sharply asymmetric — left unfixed by explicit decision.** Excellent for the
pattern actually used by 4 of the repo's ~7 real modules; a genuine violation of this dimension
for the embedded pattern. The real fix (generic module discovery + generic dispatch, replacing
`SdkModuleHost`'s hardcoded list/branches) was scoped out of this round specifically because it
touches the exact code path `OpenDaylightModule`'s live SDN mitigation depends on — see the Part 3
summary table's note.

### 5. Progressive Evaluation
**What the framework prescribes:** a programmer should be able to check partially-completed work
— compile it, run it, see *something* — without having to complete the entire task first.

**What this SDK actually does:** **The SDK provides no such facility.** There is no dry-run mode,
no workflow-validation CLI, no "does this payload class round-trip" check shipped anywhere in
`nis-thesis-sdk` or the tooling around it. This session had to build one from scratch, twice, as a
throwaway `.java` file calling `WorkflowLoader`/`WorkflowMatcher` directly to check a new workflow
YAML before any live run — proof the capability is *possible* (the matching logic has no hidden
RabbitMQ dependency), but also direct proof it does not exist as a real, supported feature. The
JUnit suite added in a prior session (`SuricataModuleParseTest`, `MaltrailModuleParseTest`, etc.)
covers payload round-trips and drop-path behavior, but nothing exists for the workflow-YAML
authoring loop specifically, which is where this session repeatedly needed it.

**Verdict: PASS** *(was: FAIL — the framework prescription was unmet; every instance of
progressive evaluation was improvised by the developer, not provided by the SDK)*. **Fixed:**
`WorkflowEngine/WorkflowDryRunTool.java` ships the exact throwaway-check pattern as a permanent,
documented tool — given a workflows directory and a sample alert JSON file, it loads workflows
and reports matches with no RabbitMQ dependency. Verified against both the Maltrail- and
Sysmon-ransomware sample alerts shipped alongside it
(`WorkflowEngine/sample_alerts/`), reproducing the exact matches confirmed manually earlier in
this project. A developer can now check a workflow condition against a sample alert as a single
documented command instead of writing a new throwaway `.java` file each time.

### 6. Premature Commitment
**What the framework prescribes:** an API should not force a programmer to make binding decisions
before they have enough information to make them well, and early decisions should not have
outsized, hard-to-reverse consequences.

**What this SDK actually does:** Choosing standalone-vs-embedded is exactly this kind of forced,
early, hard-to-reverse decision — the two paths differ in lifecycle, registration, testing
approach, and debugging technique, with no supported migration path between them once code exists.
This was made **worse, not just present**, by the embedded path specifically: choosing it
committed a new author to editing global shared state (`initializeModules()`, `dispatch()`) that
other, unrelated embedded modules depend on for correct behavior. The specific compounding
factor — the cumulative-capabilities bug that made this genuinely dangerous rather than just
inconvenient — **has been fixed** (§1.7): `initializeSingleModule` now snapshots capabilities
before/after each module's own `initialize()` call, so one embedded module's registration can no
longer silently corrupt another's.

**Verdict: FAIL, deliberately left as-is beyond the bug fix above.** The underlying forced,
hard-to-reverse pattern choice is still real — that's the root architectural issue, and fixing it
means replacing `SdkModuleHost`'s hardcoded list/dispatch with a generic mechanism, which was
scoped out of this round precisely because of its proximity to `OpenDaylightModule`'s live SDN
mitigation path (see the Part 3 summary table). What *was* fixed is the worst compounding
consequence of that choice; the choice itself remains premature and binding.

### 7. Penetrability
**What the framework prescribes:** a programmer should be able to inspect and understand the
system's internal state and behavior when something isn't working, without disproportionate
effort.

**What this SDK actually does:** Strong on passive observability — every module logs
registration, heartbeats, and every message with distinctive, greppable output, and this session
verified two full pipelines (Maltrail, and the offline workflow-match check) from console output
alone with no debugger. Previously weak on active introspection: there was no way to *ask* the
running system "what capabilities does module X currently have registered" on demand.

**Verdict: PASS** *(was: MIXED — passive observability met the prescription well, active,
on-demand introspection did not exist at all)*. **Fixed:** `webapp/backend/services/
moduleHealthService.js` now queries the live `registered_modules` Postgres table (which
`ModuleRegistry.registerModule` already wrote real capability data into, but nothing ever read)
and merges it into the module list the webapp's `Modules.js` frontend already renders — that
frontend already had a "Capabilities" column built, it was just always fed a hardcoded `[]`. The
fix also surfaces DB-registered embedded modules (e.g. `OpenDaylightModule`, `NotificationModule`)
that the filesystem-only JAR scan could never see at all, since they aren't separate JAR files.
Falls back gracefully to the previous filesystem-only behavior if Postgres is unreachable, so this
doesn't introduce a new hard dependency. (Verified by code review and `node --check` syntax
validation — no live Postgres/webapp instance was available in this environment to exercise the
query against real data; a live check is still recommended before relying on this.)

### 8. API Elaboration
**What the framework prescribes:** when a task goes beyond what the API directly hands you, the
amount of extra code required to elaborate a full solution should be small — an API that forces
large amounts of restated boilerplate for a common task has failed this dimension.

**What this SDK actually does:** Every standalone module — four of them now, independently
written across two sessions — hand-builds the identical envelope shape (`message_type`,
`event_id`, `timestamp`, `event_type`, `source_module`, `payload`) from raw `JSONObject` calls,
roughly 15-20 lines each time, with **no shared helper anywhere in `nis-thesis-sdk` covering
this**, despite `ModuleHelper` existing in the same package for a narrower, different purpose.
This is restated boilerplate by definition, confirmed by direct comparison of the four modules'
source.

**Verdict: PASS** *(was: FAIL — a textbook example of the dimension's failure mode, the same
~20 lines four separate times, with a shared SDK sitting right next to it and not addressing
it)*. **Fixed:** `AlertEnvelopeBuilder` (same fix as dimension 1) eliminates the restated
boilerplate — all four standalone modules now call one shared builder instead of reimplementing
the envelope shape each time.

### 9. API Viscosity
**What the framework prescribes:** making a small, well-understood change should require
proportionally small effort — high viscosity (a small change requiring large, invasive effort) is
a specific, named failure mode.

**What this SDK actually does:** Directly inherits the asymmetry from dimensions 1 and 4: a
change to a standalone module is local and low-effort. A change to the embedded path is high
viscosity by the framework's own definition — a single new module addition ripples into shared,
order-sensitive global state and, as concretely demonstrated by the capability-registration bug
in §1.7, can silently change the behavior of *other, already-working* modules as a side effect.
That is closer to what Cognitive Dimensions literature calls a knock-on-effect viscosity failure
than an ordinary cost-of-change tradeoff.

**Verdict: FAIL, deliberately left as-is.** Specifically and narrowly for the embedded pattern —
not a generic complaint, a demonstrated instance. The knock-on-effect part of this (the
capability-registration bug) has been fixed (§1.7); the structural viscosity itself (an embedded
module addition still edits shared, order-sensitive code) has not, for the same reason given
under Work-Step Unit and Premature Commitment above — the real fix requires replacing
`SdkModuleHost`'s hardcoded list/dispatch, which was deliberately scoped out of this round.

### 10. Consistency
**What the framework prescribes:** once part of an API is learned, that knowledge should reliably
predict the behavior of the rest of the API — similar-looking things should behave similarly.

**What this SDK actually does:** The original audit pass found damage at two different levels of
granularity simultaneously:
- **Architecturally:** learning Pattern A (standalone) predicts nothing about Pattern B (embedded
  event-subscriber) or Pattern C (embedded HTTP-ingestion) — different lifecycle, different
  registration mechanism, different debugging technique, despite all three claiming to implement
  "the SDK." **This remains true and is deliberately unfixed** (same root cause as dimensions
  3/4/6/9 above).
- **At the code level, inside a single class:** `WorkflowMatcher.evaluateCondition`'s `severity`
  condition used `.equalsIgnoreCase()` on its `!=` branch and case-sensitive `.equals()` on its
  `==` branch — two operators on the *same field*, in the *same method*, behaving by different
  rules. **This has been fixed** — the `==` branch now also uses `.equalsIgnoreCase()`, so both
  branches agree.

**Verdict: PASS on the fixable half; the architectural half remains a known, documented,
deliberately-unaddressed gap.** The fine-grained code-level inconsistency (the actual bug) is
fixed and verified (`WorkflowEngine` compiles and the offline dry-run tool still matches
correctly). The architectural three-pattern inconsistency is real, significant, and requires the
same `SdkModuleHost` redesign called out throughout Part 3 as out of scope for this round — this
dimension is marked PASS on the strength of the concrete bug fix, not as a claim that the
architecture is now uniform.

### 11. Role Expressiveness
**What the framework prescribes:** a reader should be able to tell what role a piece of code or
data plays in the overall system just by looking at it, without having to trace its usage
elsewhere.

**What this SDK actually does:** The original audit pass found three separate, concrete,
demonstrated failures — all three have since been fixed:
- ~~`MitigationCommandData.additionalParameters` is typed as a bare `String`~~ **fixed**: it is
  now a typed `MitigationParameters` object (new class in `nis-thesis-sdk`) with named fields for
  every key `SdkModuleHost`/`OpenDaylightModule` actually populate/read (`macAddress`,
  `mitigationId`, `severity`, a typed `QuarantinePolicy` nested class, plus a loosely-typed
  `extra` map for genuinely ad-hoc fields). `OpenDaylightModule` was updated to consume it
  directly instead of re-parsing a JSON string.
- ~~`RegisteredModule.getCommandQueue()` returns a queue name for every registered module~~
  **fixed**: embedded modules now register with an explicit
  `"(embedded - in-process dispatch, no queue)"` marker instead of a fabricated, never-consumed
  queue name — the field no longer implies a role it doesn't play.
- ~~`EnrichmentRequestData`'s two constructors are call-site indistinguishable~~ **fixed**:
  replaced with named static factories, `EnrichmentRequestData.forIpAddress(ip)` and
  `EnrichmentRequestData.forRequest(enrichmentType, requestId)` — the call site itself now states
  which fields get set. The one real call site (`ModuleHelper.java`) and the webapp doc example
  were both updated.

**Verdict: PASS** *(was: FAIL — three independently observed instances in three different
classes)*. All three concrete, demonstrated failures found in the first audit pass have real,
verified fixes — this was not a theoretical dimension to begin with, and the fix is equally
concrete.

### 12. Domain Correspondence
**What the framework prescribes:** the API's concepts and vocabulary should map directly onto
the vocabulary practitioners in the target domain already use, minimizing translation.

**What this SDK actually does:** This is the one dimension where the SDK genuinely meets the
prescription without qualification. `MitigationAction`'s values (`QUARANTINE`, `BLOCK_IP`,
`ISOLATE_VLAN`, `RATE_LIMIT`, `KILL_PROCESS`, `DISABLE_USER`) are exactly the vocabulary a SOC
analyst's own runbooks use, with no translation layer. Alert payload fields (`severity`,
`signature`, `threat_score`, `source_ip`) mirror the vocabulary of the tools being integrated
directly (Suricata's own EVE JSON schema, for instance) rather than inventing new terms.

**Verdict: PASS — the only unambiguous pass in this audit.** Worth stating plainly: this dimension
succeeding does not offset the failures above; each dimension measures something different, and a
strong domain vocabulary does not make the embedded path's viscosity or the workflow matcher's
inconsistency any less real.

---

# Part 4 — API Ergonomics & Cognitive Load Checklist

**Are method names predictable and native to the language ecosystem?**
Mostly yes for the formal SDK (`getName`/`initialize`/`shutdown`, `publishEvent`/
`subscribeToEvent` all read as ordinary Java bean/listener conventions). Weaker for the standalone
modules, where method names are ad hoc per module (`parseEveJsonLine`, `parseMaltrailPacket` —
same concept, different names across files, since there's no shared interface constraining them).

**Does it support modern async paradigms (promises, async/await) natively?**
**No.** `CoreSystemApi.publishEvent`/`subscribeToEvent` are both fully synchronous, blocking
calls; there is no `CompletableFuture`, no reactive-stream type, no async variant anywhere in
`nis-thesis-sdk`. Every module's own concurrency (thread pools, `ScheduledExecutorService`,
`WatchService` polling loops) is hand-rolled per module rather than provided by the SDK. This is
an honest gap: the SDK predates/ignores modern async-API conventions entirely, and a Java
developer today would reasonably expect at least an async publish option for I/O-bound event
dispatch.

**Are default configurations sensible, or do users have to set 10 flags just to start?**
**A genuine strength.** Every module's `loadConfig()` (standalone) reads a `.properties` file
with **every key defaulted in code** — a missing or partially-filled config file degrades to
sensible built-in defaults rather than failing to start (confirmed directly:
`SuricataModule.loadConfig()`, `MaltrailModule.loadConfig()`, and this round's
`Fail2banModule`/`SysmonModule` all follow this pattern). A user genuinely can start a module with
zero configuration and get reasonable behavior — no 10-flags-to-start problem here.

---

## References

[1] Green, T. R. G., & Petre, M. (1996). Usability analysis of visual programming environments:
A 'cognitive dimensions' framework. *Journal of Visual Languages & Computing*, 7(2), 131–174.
https://doi.org/10.1006/jvlc.1996.0009

[2] Clarke, S. (2005). *Describing and Measuring API Usability with the Cognitive Dimensions.*
Microsoft Corporation.

[3] Grill, T., Polacek, O., & Tscheligi, M. (2012). Methods towards API Usability: A Structural
Analysis of Usability Problem Categories. In *Human-Centered Software Engineering* (pp. 164–180).
Springer. https://doi.org/10.1007/978-3-642-34347-6_10
