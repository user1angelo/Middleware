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
going in order means you're never blocked waiting on code you haven't written yet.

### `FooGuardStandaloneSkeleton.java` (Pattern A)

| Order | TODO | What it does |
|---|---|---|
| 1 | TODO-1 (`loadConfig`) | Read the log path from the `.properties` file instead of only using the hardcoded default. Independent of the others — do it first as a warm-up. |
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
| 2 | TODO-2 (`initialize`) | Wire up `ModuleHelper`, the stub firewall client, and subscribe to `INITIATE_MITIGATION`. References TODO-3 by method reference, so write TODO-3's method signature (even with just the `throw` still in its body) before finishing this one. |
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
