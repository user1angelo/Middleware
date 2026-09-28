---
name: soar-module-builder
description: Guides a user through turning a security tool they have (an IDS, a log source, a monitoring agent, an HTTP-webhook-based product, etc.) into a new pluggable module for this repo's nis-thesis-sdk / user-defined-modules system. Use whenever the user wants to integrate a new tool with the SOAR middleware, add a new UDM (user-defined module), write a module for the SDK, or asks things like "how do I hook up <tool> to the system", "can you build the config/module for <tool>", or "I have a tool that logs to X, how do I get alerts from it into the workflow engine". Also use when the user wants to understand how the SDK/module system actually works before writing code. Runs a structured Q&A to gather the tool's details, picks the right integration pattern (defaulting to the low-risk standalone pattern), generates the module Java class + payload class + config file + tests from the bundled templates, and then compiles the result and sanity-checks the config before calling it done. Do not use this for changes to the core SDK itself (nis-thesis-sdk/), the ModuleRegistry, or WorkflowEngine internals — this skill is specifically for adding a new tool integration on top of those.
---

# SOAR Module Builder

Turns "I have a security tool" into a working, compiled module for this repo's middleware — while
teaching the user how the pieces fit together as it goes, not just handing them files.

This skill is grounded in two documents already in this repo that you should treat as authoritative:
[`THESIS_SDK_AND_SYSTEM.md`](../../../THESIS_SDK_AND_SYSTEM.md) (system architecture) and
[`SDK_USABILITY_AUDIT.md`](../../../SDK_USABILITY_AUDIT.md) (what's easy, what's risky, and why —
this is where the pattern recommendation below comes from). The deeper narrative version of both,
condensed for this workflow, lives in `references/sdk-architecture.md` — point the user there if
they want to understand the *why*, not just get files.

## Step 0: Explain the shape of the system, briefly

Before diving into questions, give the user a two-or-three-sentence mental model so the Q&A makes
sense as you go, rather than feeling like a form:

> Your tool → a new module (Java) → RabbitMQ → ModuleRegistry (which broadcasts alerts and routes
> commands) → WorkflowEngine (YAML rules that watch for alert patterns and trigger responses).
> Every module publishes the same JSON envelope shape (`message_type`, `event_id`, `timestamp`,
> `event_type`, `source_module`, `payload`), which is what lets the WorkflowEngine treat alerts
> from completely different tools the same way.

Point to `references/sdk-architecture.md` for anyone who wants the full picture before continuing.

## Step 1: Structured Q&A

Ask these questions explicitly rather than trying to infer them from a free-text description — the
user asked for this to be a checklist, and getting these answers up front avoids generating a
module with the wrong ingestion mechanism or a config that silently breaks module-to-dashboard
correlation later (see the `module.id` gotcha in Step 3). Use `AskUserQuestion` where it maps
cleanly to a short list of choices; for open-ended items (tool name, sample log line) just ask in
prose. It's fine to batch several into one question turn.

1. **Tool name/vendor** — becomes the Java class prefix and the `.properties` filename. Pick
   something short and code-safe (e.g. "Zeek", not "Zeek Network Security Monitor v6.0").
2. **How does the tool expose its data?** This is the single most important answer — it decides
   which module template you use:
   - **Tails a local log file** (plain text or JSON-lines) → file-tail template
   - **Pushes UDP packets to a configured destination** (e.g. syslog-style, Logstash-style) → UDP
     template
   - **Calls a webhook / sends HTTP POST notifications** → HTTP template
   - Something else (database polling, a vendor SDK/API, etc.) — still buildable, but flag to the
     user that none of the three bundled templates fit exactly and you'll need to adapt the
     file-tail template's structure (registration/heartbeat/command-listener stay the same
     regardless of ingestion mechanism — only the ingestion method itself changes).
3. **A sample log line, JSON payload, or webhook body**, if the user has one handy. This is what
   lets you actually fill in field mapping instead of leaving placeholders. If they don't have a
   sample, ask what fields the tool is documented to provide (source IP, severity, signature/rule
   name, category, etc.) and proceed with best-effort mapping, clearly marked as such in the code
   comments.
4. **What severity scale does the tool use**, and how should it map to this system's
   `critical` / `high` / `medium` / `low` scale? (See the case-sensitivity note in Step 3 — get an
   explicit mapping now rather than guessing later.)
5. **Module type/category** — `network_security`, `host_security`, or another short category. This
   feeds `module.type` and influences how the module is grouped in the dashboard.
6. **Capabilities** — a short comma list describing what this module produces or does (e.g.
   `network_ids,alert_generation,packet_analysis`). This drives workflow-to-module routing later,
   so it's worth being specific rather than generic.

Don't ask about RabbitMQ host/port/credentials, heartbeat interval, or other infrastructure
defaults — the templates default those exactly like every existing module in this repo (localhost,
5672, `user`/`password`, 30s heartbeat), and the properties file is designed to be edited by hand
later for a real deployment. Asking about them here just adds friction for no benefit.

## Step 2: Decide the integration pattern

There are three genuinely different ways to plug a module into this system. Full detail is in
`references/sdk-architecture.md`; the short version, which is enough to make the call:

**Default to the standalone pattern (Pattern A) unless the user gives you a specific reason not
to.** It's a plain Java process with its own `main()`, own RabbitMQ connection, and its own
ingestion loop. It requires editing **zero** shared files anywhere in the repo — every module
built this way so far (Suricata, Maltrail, Fail2ban, Sysmon) took roughly 5-10 minutes once the
pattern was understood, with no risk to any other module. This is true regardless of whether
ingestion is file-tail, UDP, or HTTP — a standalone process can run its own embedded HTTP listener
just as easily as it can tail a file; it still doesn't need to touch anything shared.

**Only consider the embedded pattern (Pattern B or C)** if the user's actual requirement is that
the module needs to *react* to events already flowing through the system (subscribe to
`INITIATE_MITIGATION`-style commands and act on them, the way `OpenDaylightModule` does for SDN
isolation) rather than just producing new alerts. If that's genuinely the case:

- Say so explicitly and explain the tradeoff before writing any code: the embedded path requires
  hand-editing two hardcoded chokepoints in
  `ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java` — the
  `initializeModules()` module list and the `dispatch()` event-type branches — both shared by every
  other embedded module, including `OpenDaylightModule`'s live SDN mitigation routing. This is
  documented in `SDK_USABILITY_AUDIT.md` (Part 3.5) as a deliberately-unfixed high-risk area, not
  an oversight.
- Confirm the user actually wants this before touching `SdkModuleHost.java`. This skill's bundled
  templates only cover Pattern A; walking through a Pattern B/C addition means reading
  `OpenDaylightModule.java` and `SdkModuleHost.java` directly as your model, being conservative
  about what you change, and telling the user clearly what shared state you touched.

In the overwhelming majority of cases — "I have a tool that produces alerts and I want them in the
system" — Pattern A is correct and this ambiguity never needs to come up with the user at all.

## Step 3: Generate the files

Assume Pattern A from here (see Step 2 for the Pattern B/C exception). All templates live in
`templates/` in this skill directory. Read the ones you need, fill in the placeholders, and write
the result to the real repo paths below. Use PascalCase for `{{ToolName}}` (e.g. `Wazuh`) and
lowercase-hyphenated for `{{tool-name}}` (e.g. `wazuh`) consistently — they appear in different
files and must match.

| File to write | Template | Notes |
|---|---|---|
| `user-defined-modules/src/main/java/com/nis1/thesis/udm/{{ToolName}}Module.java` | `templates/Module-filetail.java.template`, `templates/Module-udp.java.template`, or `templates/Module-http.java.template` (pick per Step 1 answer) | Main module logic |
| `user-defined-modules/src/main/java/com/nis1/thesis/udm/{{ToolName}}AlertData.java` | `templates/AlertData.java.template` | Payload POJO |
| `user-defined-modules/config/{{tool-name}}-module.properties` | `templates/module.properties.template` | See the `module.id` warning below |
| `user-defined-modules/src/test/java/com/nis1/thesis/udm/{{ToolName}}ModuleParseTest.java` | `templates/ModuleParseTest.java.template` | Optional but recommended |
| `user-defined-modules/src/test/java/com/nis1/thesis/udm/{{ToolName}}AlertDataTest.java` | `templates/AlertDataTest.java.template` | Optional but recommended |
| `user-defined-modules/{{ToolName}}_MODULE_README.md` | `templates/README.md.template` | Optional; mirrors `MALTRAIL_MODULE_README.md` |

**Field-name discipline matters more than it looks like it should.** `WorkflowMatcher` (the
component that decides whether an alert triggers a workflow) matches on the payload's field names
directly — `severity`, `alert_type`, `category`, `threat_score`, `source_ip`, etc. Every existing
`*AlertData` class reuses these exact names specifically so new modules plug into existing workflow
YAML without anyone touching `WorkflowMatcher`. Don't invent new field names for concepts that
already have one in `AlertData.java.template`'s common block — only add new fields for genuinely
tool-specific data with no existing equivalent (the way `MaltrailAlertData` adds `trail` for its
matched threat-intel indicator).

**The `module.id` / filename constraint is load-bearing, not stylistic.** `module.id` inside the
`.properties` file must exactly equal the filename minus `.properties` (e.g.
`wazuh-module.properties` → `module.id=wazuh-module`). The web dashboard correlates database
records with config files by this value. Get this wrong and the module still runs, registers, and
publishes alerts — it just silently fails to show up correctly in the dashboard, which is a
miserable thing to debug later. Double check it before moving on.

**Severity normalization**: normalize whatever scale the tool uses to lowercase
`critical`/`high`/`medium`/`low` inside the module's mapping function, not at the raw source. This
sidesteps a documented case-sensitivity inconsistency between `WorkflowMatcher`'s `==` and `!=`
severity comparisons (see `references/sdk-architecture.md` for detail) — every existing module
already normalizes this way, so it's the safe, consistent choice.

As you generate each file, briefly say what it does and how it fits into the pipeline described in
Step 0 — that's the "teach as you go" part of this skill, not just an afterthought.

## Step 4: Compile and sanity-check

Don't consider the task done once files are written — verify them.

1. **Build it.** The real, current build path for standalone modules in this repo is Maven, not a
   hand-rolled `javac` invocation (the `compile_*.ps1` scripts in `user-defined-modules/` are for a
   *different*, embedded-pattern module type and don't apply here — don't reach for those). Run:
   ```
   mvn -pl user-defined-modules compile
   ```
   from the repo root. Fix any compile errors before proceeding — most first-pass errors are
   missing imports or a field name typo between `{{ToolName}}Module.java` and
   `{{ToolName}}AlertData.java`.
2. **Run the tests**, if you generated them: `mvn -pl user-defined-modules test`.
3. **Sanity-check the config file** by hand against this checklist — these are the mistakes that
   compile fine but break silently at runtime:
   - `module.id` exactly matches the properties filename (see Step 3)
   - Every key the module's `loadConfig()` reads has a corresponding line in the `.properties` file
     (or the module has a sane in-code default — check `loadConfig()`'s `getProperty(key, default)`
     calls line up)
   - `module.capabilities` is a comma list with no stray whitespace issues that would break the
     `split(",")` + `.trim()` parsing every existing module uses
   - The tool-specific source path/port (log path, UDP port, HTTP port+route) matches what you'll
     actually point the real tool at
4. **Tell the user how to actually run and test it end-to-end**, mirroring the pattern in
   `MALTRAIL_MODULE_README.md`: start RabbitMQ + ModuleRegistry, run
   `java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*" com.nis1.thesis.udm.{{ToolName}}Module`
   from inside `user-defined-modules/` (config is loaded from a CWD-relative path, so this matters),
   then either point the real tool at the module or write a small test script mirroring
   `scripts/send_test_maltrail_event.py` to simulate one event.

Report back concretely: what compiled, what you couldn't verify without a live RabbitMQ broker
(most likely, in a dev sandbox — say so rather than implying it was tested end-to-end), and any
config values the user still needs to fill in for their real environment (log paths, ports,
credentials).

## Reference material

- `references/sdk-architecture.md` — the fuller "how this all actually works" narrative, the three
  integration patterns in detail, and the specific gotchas (severity case-sensitivity, the
  `threat_score` operator limitation, non-recursive workflow directory scanning) worth knowing
  about before writing workflow YAML for a new alert type.
- [`THESIS_SDK_AND_SYSTEM.md`](../../../THESIS_SDK_AND_SYSTEM.md) — system-wide architecture doc.
- [`SDK_USABILITY_AUDIT.md`](../../../SDK_USABILITY_AUDIT.md) — the full usability audit this
  skill's pattern recommendation and gotcha list are drawn from.
