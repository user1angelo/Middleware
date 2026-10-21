# Testing & Evaluation Guide

How to actually run this evaluation kit, start to finish: orientation, filling in the blanks,
compiling, and rating the SDK. Written for someone who has not seen this repo before — if you
already know the codebase, skim the headers and jump to whichever part you need.

The kit itself (what it is, why it exists) is described briefly in [README.md](README.md); this
guide is the detailed walkthrough of the process.

---

## Before you start

You need:
- **Java 17+** and **Maven** (`java -version`, `mvn -version` — if either fails, they're not
  installed or not on `PATH`).
- A text editor or IDE that can open this repo. Not required to compile, but makes navigating
  between the skeleton and its reference module far less painful.
- No RabbitMQ, no PostgreSQL, no running services of any kind. Everything in this guide up through
  compilation works completely offline. (There's an optional live-run step at the end of Part 3
  for anyone curious to see it actually publish a message — it's not required for the evaluation.)

Budget roughly **60–90 minutes** total for a first pass: ~15 minutes reading, ~45 minutes filling
in both skeletons, ~10 minutes compiling/fixing errors, ~20 minutes writing up ratings. Going
slower is fine and arguably better — the friction you notice *while* working is the actual data
this exercise produces.

---

## File map — exact paths for everything you'll touch

All paths below are relative to the repo root. Everything you **edit** lives inside
`sdk-usability-evaluation/`; everything you **read for reference** lives in `nis-thesis-sdk/` and
`user-defined-modules/`.

**Files you edit:**

| File | What's in it |
|---|---|
| `sdk-usability-evaluation/src/main/java/com/nis1/thesis/eval/FooGuardStandaloneSkeleton.java` | Pattern A — all 6 TODOs live here: TODO-1 `loadConfig`, TODO-2 `mapSeverity`, TODO-3 `categorize`, TODO-4 `calculateThreatScore`, TODO-5 `buildFooGuardAlertJson`, TODO-6 `parseFooGuardLine`. |
| `sdk-usability-evaluation/src/main/java/com/nis1/thesis/eval/FooGuardMitigationSkeleton.java` | Pattern B — all 4 TODOs live here: TODO-1 `getName`, TODO-2 `initialize`, TODO-3 `onMitigationCommand`, TODO-4 `shutdown`. |

**Files you read but don't edit:**

| File | Why you'd open it |
|---|---|
| `sdk-usability-evaluation/config/foo-module.properties` | The `.properties` file TODO-1 reads from. It already contains a `foo.log_path=sample-data/foo.log` line — TODO-1 is about making `loadConfig()` actually *read* that key (one line, next to the `RABBITMQ_*`/`WORKFLOW_QUEUE` reads already there), not about adding the key to the file. |
| `sdk-usability-evaluation/sample-data/foo.log` | 3 sample FooGuard log lines — reference for `FooGuardEvent`'s field shapes, and what you'd append to for the optional live-run in Part 3. |
| `sdk-usability-evaluation/src/main/java/com/nis1/thesis/eval/FooGuardAlertData.java` | Already complete — the payload class TODO-5 populates. |
| `user-defined-modules/src/main/java/com/nis1/thesis/udm/SuricataModule.java` | Model for TODO-1 (`loadConfig()`/`EVE_JSON_PATH`), TODO-2 (`mapSeverityLevel`), TODO-3 (`categorizeFromSignature`), TODO-4 (`calculateThreatScore`). |
| `user-defined-modules/src/main/java/com/nis1/thesis/udm/MaltrailModule.java` | Model for TODO-2 (`mapSeverity`), TODO-3 (`categorizeFromInfo`), TODO-4 (`calculateThreatScore`), TODO-6 (`parseMaltrailPacket`). |
| `user-defined-modules/src/main/java/com/nis1/thesis/udm/OpenDaylightModule.java` | Model for all of Pattern B — `getName()`, `initialize()`, `onMitigationCommand()`, `shutdown()`. |
| `nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/AlertEnvelopeBuilder.java` | The builder TODO-5 calls. |
| `ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java` | Read-only reference for Pattern B's intro comment (the two hardcoded chokepoints) — you're asked to read this, not edit it. |

---

## Part 1 — Explanation (read this before touching code)

### What you're actually doing

`nis-thesis-sdk` is the API this thesis is centered on: the contract that lets a security tool
(an IDS, a log source, an SDN controller, anything) plug into this repo's SOAR-style middleware.
Rather than asking you to read a spec and give an opinion, this kit has you **use** the SDK to
build two small, realistic pieces of integration code for a fictional tool called **FooGuard** —
then asks you to rate the SDK using **Clarke's (2005) Cognitive Dimensions of Notations**, a
usability framework built specifically for evaluating APIs (adapted from Green & Petre's original
1996 Cognitive Dimensions framework for visual/programming notations). You're producing a
first-hand, evidence-based usability rating, not a first impression.

### The system, in one picture

```
Tool → Module (Java, built with nis-thesis-sdk) → RabbitMQ → ModuleRegistry
                                                                    │
                                              ┌─────────────────────┼─────────────────────┐
                                              ▼                                           ▼
                                    ThreatContextStore                              WorkflowEngine
                                    (persists alerts)                    (matches alerts to YAML rules,
                                                                           emits response commands)
                                                                                    │
                                                                                    ▼
                                                                     ModuleRegistry routes the command
                                                                     to whichever module can handle it
                                                                                    │
                                                                                    ▼
                                                                       target module executes a response
```

Every message on the bus shares one JSON envelope: `message_type`, `event_id`, `timestamp`,
`event_type`, `source_module`, `payload`.

### The two integration patterns you'll experience

- **Pattern A — Standalone.** A module is its own Java process: it connects to RabbitMQ itself,
  registers itself, sends heartbeats, and pushes alerts it produces. Real example:
  `user-defined-modules/src/main/java/com/nis1/thesis/udm/SuricataModule.java`. Your exercise:
  `src/main/java/com/nis1/thesis/eval/FooGuardStandaloneSkeleton.java`.
- **Pattern B — Embedded.** A module implements the SDK's `PluggableModule` interface and is
  hosted *inside* the registry process, reacting to events rather than running independently. Real
  example: `user-defined-modules/src/main/java/com/nis1/thesis/udm/OpenDaylightModule.java`. Your
  exercise: `src/main/java/com/nis1/thesis/eval/FooGuardMitigationSkeleton.java`.

You'll build one small piece of each, which is what lets you rate dimensions like **Work-Step
Unit** and **Premature Commitment** from direct comparison instead of taking anyone's word for it.

### Required reading, in this order

1. **`SDK_USABILITY_AUDIT.md`** (repo root) — at minimum, Part 1 (how the SDK actually works) and
   the 12 dimension definitions in Part 3. This is the framework and the vocabulary you'll use in
   Part 4 of this guide. You do not need to read every dimension's full writeup yet — just enough
   to recognize each name and what it's asking.
2. **`SuricataModule.java`** and **`MaltrailModule.java`** — two real, complete Pattern A modules.
   Skim both; they're your model for `FooGuardStandaloneSkeleton.java`.
3. **`OpenDaylightModule.java`** — a real, complete Pattern B module. Your model for
   `FooGuardMitigationSkeleton.java`.
4. **`FooGuardAlertData.java`** (in this kit, already complete) — a small worked example of how an
   existing module's field-naming choices were made, before you make your own.

Don't skip straight to Part 2. The point of the exercise is comparing your experience against
real, working code — not writing Java in a vacuum.

---

## Part 2 — Fill in the blanks

Work through the TODOs **in this order** within each file — later TODOs call earlier ones, so
going in order means each step builds on logic you've already implemented and can trust, rather
than reasoning about a chain of calls you haven't written yet. (Every method already compiles as a
placeholder `throw` regardless of order, so you're never *compile*-blocked either way — the order
is about staying oriented, not about unblocking the build.)

### `FooGuardStandaloneSkeleton.java` (Pattern A)

| Order | TODO | What it does |
|---|---|---|
| 1 | TODO-1 (`loadConfig`) | Read the log path from the `.properties` file (`sdk-usability-evaluation/config/foo-module.properties`, key `foo.log_path`) instead of only using the hardcoded default. Independent of the others — do it first as a warm-up. |
| 2 | TODO-2 (`mapSeverity`) | Map FooGuard's `P1`–`P4` scale to `critical`/`high`/`medium`/`low`. |
| 3 | TODO-3 (`categorize`) | Turn FooGuard's `rule` slug into a category/alert type. |
| 4 | TODO-4 (`calculateThreatScore`) | Derive a 0–100 score from severity + category. |
| 5 | TODO-5 (`buildFooGuardAlertJson`) | The core exercise: populate `FooGuardAlertData` using TODOs 2–4, then build the envelope with `AlertEnvelopeBuilder`. |
| 6 | TODO-6 (`parseFooGuardLine`) | Validate a raw log line, call TODO-5, and publish the result. |

Each TODO's doc comment names the exact method in `SuricataModule.java` or `MaltrailModule.java`
to use as your model — open that method side-by-side rather than guessing from memory.

### `FooGuardMitigationSkeleton.java` (Pattern B)

| Order | TODO | What it does |
|---|---|---|
| 1 | TODO-1 (`getName`) | One line — return the module's display name. |
| 2 | TODO-2 (`initialize`) | Wire up `ModuleHelper`, the stub firewall client, and subscribe to `INITIATE_MITIGATION`. References TODO-3 (`onMitigationCommand`) by method reference — its signature is already provided (with a placeholder `throw`), so this compiles fine before TODO-3 is implemented; you just need to know what it will receive. |
| 3 | TODO-3 (`onMitigationCommand`) | The reactive handler — cast the event payload, check the action type, call the stub enforcement client. |
| 4 | TODO-4 (`shutdown`) | Two lines — flip a flag, log it. |

Before you write any code here, read the block comment at the top of the file about
`SdkModuleHost.java`'s two hardcoded chokepoints — you're asked to *read*, not edit, that file.
That reading is itself part of the exercise (see Part 4, Premature Commitment / API Viscosity).

### While you work

Keep a running note — a scratch text file, comments in the code, whatever's least friction for
you — of moments where you:
- had to open a second or third file to figure out what to do,
- weren't sure what a method was supposed to return until you inferred it from a caller,
- got something wrong on the first try and had to backtrack,
- found something surprisingly easy or obvious,
- wanted a capability the SDK didn't seem to offer.

This is the raw material for Part 4. Rating a dimension convincingly from memory an hour later is
much harder than pulling from three or four concrete notes taken in the moment.

---

## Part 3 — Compilation

### Compile

From the repo root:

```bash
mvn -pl sdk-usability-evaluation compile
```

**What success looks like** — near the bottom of the output:

```
[INFO] Reactor Summary for NIS1 Thesis - SOAR Framework 1.0-SNAPSHOT:
[INFO]
[INFO] NIS1 Thesis - SOAR Framework ....................... SUCCESS
[INFO] nis-thesis-sdk ..................................... SUCCESS
[INFO] SDK Usability Evaluation Kit ....................... SUCCESS
[INFO] BUILD SUCCESS
```

If you see `BUILD FAILURE`, scroll up to the first `[ERROR]` line — that's the actual problem;
everything printed after it is noise from Maven giving up.

For a fully clean rebuild (clears cached class files first, useful if you're not sure whether an
old compile is masking a current error):

```bash
mvn -pl sdk-usability-evaluation clean compile
```

### Common errors and what they usually mean here

| Error | Likely cause |
|---|---|
| `cannot find symbol` on a field/method name | Typo, or you're calling a getter/setter that doesn't exist on `FooGuardAlertData` — check its exact method names. |
| `missing return statement` | A non-`void` TODO method has a code path that doesn't return (or you left the `throw` in alongside new code that falls through past it — remove the `throw` once you've written the real body). |
| `incompatible types: Object cannot be converted to MitigationCommandData` | In `onMitigationCommand`, you skipped the `instanceof` check before casting `event.getData()`. |
| `unreported exception ... must be caught or declared to be thrown` | You're calling something that throws a checked exception (e.g. `IOException` from `channel.basicPublish`) without a `try/catch` or a `throws` clause — look at how `parseFooGuardLine`'s sibling methods in `MaltrailModule.java` handle this. |
| Everything compiles but nothing seems to happen when you imagine running it | That's expected without a live RabbitMQ broker — compiling is the bar for this exercise, not a live end-to-end run (see below). |

### Optional: running it live

Not required for the evaluation, but satisfying if you want to see an actual message published.
Needs a RabbitMQ broker reachable at `localhost:5672` (see the root `TESTING_GUIDE.md` for setup)
and `ModuleRegistryLifecycleManager` running. From `sdk-usability-evaluation/`:

```bash
mvn -pl sdk-usability-evaluation package
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*:$(find ~/.m2 -name 'amqp-client-*.jar' | head -1):$(find ~/.m2 -name 'json-2*.jar' | head -1):$(find ~/.m2 -name 'gson-*.jar' | head -1)" \
  com.nis1.thesis.eval.FooGuardStandaloneSkeleton
```

Then append a new line to `sample-data/foo.log` (matching the existing format) and watch the
console for a `📤 Published` line, if you kept that logging style, or your own equivalent.

`FooGuardMitigationSkeleton` (Pattern B) can't be run standalone this way — by design, it only
runs hosted inside `SdkModuleHost`, which is exactly the Work-Step Unit friction Part 4 asks you to
rate. You've already gathered what you need for that rating just by reading its header comment and
`SdkModuleHost.java`; you don't need to actually get it running.

---

## Part 4 — Evaluation

### The framework

Clarke's Cognitive Dimensions, as applied to this SDK in `SDK_USABILITY_AUDIT.md`:

| # | Dimension | One-line reminder |
|---|---|---|
| 1 | Abstraction Level | Are the API's abstractions pitched at the right level for the task — not too primitive, not too aggregate? |
| 2 | Learning Style | Can you learn incrementally, trying one piece at a time, or must you absorb everything before anything works? |
| 3 | Working Framework | How much background knowledge do you need before the API's own operations make sense? |
| 4 | Work-Step Unit | Is a small, intentional change actually small to make, or does it force a large, all-or-nothing commitment? |
| 5 | Progressive Evaluation | Can you check partially-completed work (compile, run, see *something*) without finishing everything first? |
| 6 | Premature Commitment | Are you forced into early, hard-to-reverse decisions before you have enough information to make them well? |
| 7 | Penetrability | Can you inspect and understand what's happening when something isn't working, without disproportionate effort? |
| 8 | API Elaboration | When a task goes beyond what the API hands you directly, how much extra boilerplate do you need to write? |
| 9 | API Viscosity | Does a small, well-understood change require proportionally small effort, or does it ripple outward? |
| 10 | Consistency | Once you've learned one part, does that knowledge reliably predict how the rest behaves? |
| 11 | Role Expressiveness | Can you tell what a piece of code/data is for just by looking at it, without tracing its usage elsewhere? |
| 12 | Domain Correspondence | Does the API's vocabulary match the vocabulary practitioners in this domain already use? |

### How to rate

1. **Do this independently for each pattern first, then compare.** Rate Pattern A (from
   `FooGuardStandaloneSkeleton.java`) and Pattern B (from `FooGuardMitigationSkeleton.java`)
   separately for every dimension before you look at whether they agree. Several dimensions in the
   real audit are asymmetric — good for one pattern, poor for the other — and you'll only notice
   that if you rate them independently rather than forming one blended impression.
2. **Use a three-way scale: PASS / MIXED / FAIL**, matching `SDK_USABILITY_AUDIT.md`'s own scale.
   This isn't the only valid scale (a 1–5 Likert works too, and gives you more statistical
   granularity if you're aggregating across several evaluators for the thesis), but matching the
   audit's scale means your fresh rating and the audit's existing one are directly comparable —
   which is itself a useful check (see step 4).
3. **Back every rating with something concrete from Part 2's notes** — a specific TODO, a specific
   moment you got stuck or found something easy, the way the audit backs every dimension with a
   file/line citation. "Consistency: MIXED — TODO-3's categorization felt arbitrary, but TODO-2's
   severity mapping had an obvious model to copy" is useful. "Consistency: fine" is not.
4. **Compare your rating to the audit's own verdict for that dimension**, once you've written yours
   down (not before — don't let it anchor you). Agreement is a useful confirmation. Disagreement is
   *more* useful — it usually means either the audit's finding doesn't hold up from a fresh
   developer's perspective, or your exercise didn't happen to touch the part of the API where that
   finding lives (worth noting explicitly either way).

### Suggested write-up format

A simple table works well and stays easy to aggregate across multiple evaluators:

| Dimension | Pattern A rating | Pattern A evidence | Pattern B rating | Pattern B evidence |
|---|---|---|---|---|
| Abstraction Level | | | | |
| Learning Style | | | | |
| Working Framework | | | | |
| Work-Step Unit | | | | |
| Progressive Evaluation | | | | |
| Premature Commitment | | | | |
| Penetrability | | | | |
| API Elaboration | | | | |
| API Viscosity | | | | |
| Consistency | | | | |
| Role Expressiveness | | | | |
| Domain Correspondence | | | | |

Add a short free-text section underneath for anything that doesn't fit neatly into one dimension —
usability findings rarely respect the framework's boundaries perfectly, and a forced-fit rating is
less useful than an honest note in the margin.

### What happens to this afterward

If you're running this with multiple evaluators, collect each person's table plus their raw Part 2
notes (not just the final ratings) — the notes are what make it possible to later check *why*
different evaluators disagreed on a dimension, rather than just knowing that they did.

---

## Part 5 — Reference Solutions (Answer Key)

**Read this only after you've attempted every TODO yourself.** The entire point of Part 2 is
noticing your own friction firsthand — where you got stuck, what you had to infer, what felt
obvious — and that data disappears if you copy these first. Use this section to check your work
afterward, not as a shortcut.

These are *one* reasonable, working implementation each — not "the" official answer. Several TODOs
(TODO-2's default-severity choice, TODO-3's categorization keywords, TODO-4's scoring weights) are
open design decisions with no single correct answer; `SuricataModule`, `MaltrailModule`, and this
reference solution all make different, equally defensible choices. What matters for your rating is
whether the SDK gave you enough to make and justify a decision, not whether you match this file
byte-for-byte.

### `FooGuardStandaloneSkeleton.java`

**TODO-1 — `loadConfig()`**
```java
FOOGUARD_LOG_PATH = props.getProperty("foo.log_path", FOOGUARD_LOG_PATH);
```
One line, inserted where the comment block already marks TODO-1 — same pattern as the
`RABBITMQ_*`/`WORKFLOW_QUEUE` reads immediately above it in the same method.

**TODO-2 — `mapSeverity`**
```java
private static String mapSeverity(String fooPriority) {
    if (fooPriority == null || fooPriority.isBlank()) {
        return "medium";
    }
    switch (fooPriority.trim().toUpperCase()) {
        case "P1": return "critical";
        case "P2": return "high";
        case "P3": return "medium";
        case "P4": return "low";
        default:   return "medium";
    }
}
```
Default choice: `"medium"` for absent/unrecognized input — neither over-reacting (like Maltrail's
`"high"` default, justified there by every Maltrail hit already being a confirmed trail match,
which isn't true for FooGuard) nor under-reacting (a silent `"low"` could bury a real detection).
Worth stating explicitly in your write-up, since the SDK gives no guidance either way — see the
Clarke-dimension note earlier about this being closer to a Consistency finding than a Role
Expressiveness one.

**TODO-3 — `categorize`**
```java
private static String categorize(String rule) {
    if (rule == null || rule.isBlank()) {
        return "fooguard_unknown";
    }
    String lower = rule.toLowerCase();
    if (lower.contains("ransomware") || lower.contains("c2")) {
        return "ransomware";
    } else if (lower.contains("brute-force") || lower.contains("bruteforce")) {
        return "brute_force";
    } else if (lower.contains("injection")) {
        return "web_attack";
    } else if (lower.contains("scan") || lower.contains("recon")) {
        return "reconnaissance";
    } else if (lower.contains("exploit")) {
        return "exploit";
    }
    return "fooguard_" + lower.replace('-', '_');
}
```
Keyword-matching, same shape as `SuricataModule.categorizeFromSignature` /
`MaltrailModule.categorizeFromInfo` — falls back to a synthesized `fooguard_<rule>` category
instead of a bare `"unknown"`, so an unmatched rule is still traceable to its raw slug.

**TODO-4 — `calculateThreatScore`**
```java
private static int calculateThreatScore(String severity, String category) {
    int baseScore;
    switch (severity) {
        case "critical": baseScore = 90; break;
        case "high":     baseScore = 70; break;
        case "medium":   baseScore = 45; break;
        default:         baseScore = 20;
    }
    if ("ransomware".equals(category) || "exploit".equals(category)) {
        baseScore += 10;
    } else if ("brute_force".equals(category)) {
        baseScore += 5;
    }
    return Math.min(100, baseScore);
}
```
Same baseline-plus-category-bump shape as `MaltrailModule.calculateThreatScore`.

**TODO-5 — `buildFooGuardAlertJson`**
```java
static JSONObject buildFooGuardAlertJson(FooGuardEvent event) {
    String severity = mapSeverity(event.severity);
    String category = categorize(event.rule);

    FooGuardAlertData payloadData = new FooGuardAlertData();
    payloadData.setAlertId(generateAlertId());
    payloadData.setSignature(event.rule);
    payloadData.setSourceIp(event.srcIp);
    payloadData.setDestinationIp(event.dstIp);
    payloadData.setSourcePort(event.srcPort);
    payloadData.setDestinationPort(event.dstPort);
    payloadData.setProtocol(event.proto);
    payloadData.setSeverity(severity);
    payloadData.setCategory(category);
    payloadData.setAlertType(category);
    payloadData.setThreatScore(calculateThreatScore(severity, category));
    payloadData.setConfidenceScore(85);
    payloadData.setFooPriority(event.severity);

    String payloadJson = gson.toJson(payloadData);

    return AlertEnvelopeBuilder.create()
            .eventType("alerts.network.fooguard")
            .sourceModule(MODULE_NAME)
            .payload(new JSONObject(payloadJson))
            .build();
}
```
Note what's *not* here compared to `MaltrailModule.buildMaltrailAlertJson`: no `.timestamp(...)` or
`.telemetry(...)` calls on the builder. Both are optional (timestamp defaults to `Instant.now()`),
so this compiles and runs fine without them — but it's worth noticing, when you rate API
Elaboration, that the builder lets you skip telemetry silently rather than prompting for it, which
is a real design tradeoff either way.

**TODO-6 — `parseFooGuardLine`**
```java
static void parseFooGuardLine(String line, Channel channel) {
    try {
        if (!line.trim().startsWith("{") || !line.trim().endsWith("}")) {
            System.err.println("Rejected FooGuard line - not JSON: " + line);
            return;
        }

        FooGuardEvent event = gson.fromJson(line, FooGuardEvent.class);

        if (event.srcIp == null || event.dstIp == null) {
            System.err.println("FooGuard event missing required fields (src/dst): " + line);
            return;
        }

        JSONObject alert = buildFooGuardAlertJson(event);
        channel.basicPublish("", WORKFLOW_QUEUE, null,
                alert.toString().getBytes(StandardCharsets.UTF_8));

        System.out.println("Published FooGuard alert: " + event.rule
                + " [Severity: " + alert.getJSONObject("payload").optString("severity") + "]");
    } catch (Exception e) {
        System.err.println("Failed to parse FooGuard line: " + e.getMessage());
    }
}
```
Same validate → deserialize → check-required-fields → build → publish shape as
`MaltrailModule.parseMaltrailPacket` + `publishMaltrailAlert`, just collapsed into one method
instead of two.

### `FooGuardMitigationSkeleton.java`

**TODO-1 — `getName`**
```java
@Override
public String getName() {
    return moduleName;
}
```

**TODO-2 — `initialize`**
```java
@Override
public void initialize(CoreSystemApi api) {
    this.api = api;
    this.helper = new ModuleHelper(api);
    this.firewallClient = new FooGuardFirewallClient(helper, getName());
    this.running = true;

    api.subscribeToEvent("INITIATE_MITIGATION", this::onMitigationCommand);

    helper.log(getName(), "INFO", "FooGuardMitigationSkeleton initialized and subscribed to INITIATE_MITIGATION");
}
```

**TODO-3 — `onMitigationCommand`**
```java
private void onMitigationCommand(Event<?> event) {
    if (!running) {
        return;
    }

    Object data = event.getData();
    if (!(data instanceof MitigationCommandData)) {
        return;
    }

    MitigationCommandData command = (MitigationCommandData) data;
    String targetHost = command.getTargetHost();
    MitigationAction action = command.getAction();

    if (action != MitigationAction.BLOCK_IP) {
        helper.log(getName(), "INFO", "Ignoring unsupported action: " + action);
        return;
    }

    boolean success = firewallClient.blockIp(targetHost);
    helper.log(getName(), "INFO", "Block IP " + targetHost + " -> " + (success ? "SUCCESS" : "FAILED"));
}
```
Logging (rather than silently dropping) unsupported actions was the choice made here — the TODO-3
doc comment itself asks you to decide which is "the more honest choice," so back whichever you pick
with a reason in your Role Expressiveness rating.

**TODO-4 — `shutdown`**
```java
@Override
public void shutdown() {
    running = false;
    helper.log(getName(), "INFO", "Shutting down FooGuardMitigationSkeleton");
}
```

### Verifying against this answer key

```bash
mvn -pl sdk-usability-evaluation compile
```
should still succeed with `BUILD SUCCESS` — these implementations were compiled against this exact
kit before being written up here. If your own version compiles but produces a different threat
score, category, or default severity than the reference above, that's expected and fine (see the
open-design-decision note at the top of this Part) — what matters is that you made a deliberate,
justifiable choice, not that you match this exactly.
