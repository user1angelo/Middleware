# Testing Guide - How to Verify Everything That Changed

This guide assumes you know nothing about this codebase, and that you're running it on
**Ubuntu** (in a VM). It walks through checking all the work from this session, starting with
the easiest checks (nothing extra to install) and working up to a full live end-to-end test
(needs RabbitMQ running).

**You do not need to do every tier.** Tier 1 alone proves the code is correct. Tiers 2-4 prove
it actually works when wired up to a real message broker, which is nice to see but optional.

Every command below is a **bash** command, run from the repo root (wherever you cloned/copied
this project on the Ubuntu VM - the guide just calls that `$REPO`). Replace `$REPO` with your
actual path, or `cd` into it once and run `export REPO=$(pwd)` so you can copy-paste the rest
verbatim.

---

## What you need before starting

Check what's already installed:

```bash
java -version      # need 17 or newer
mvn -version        # Maven
python3 --version
```

- **Java 17+ and Maven** - required for everything in this guide.
- **Python 3** - only needed for two small test scripts (Tier 1.4 and Tier 3).
- **RabbitMQ** - only needed for Tier 3 (the full live test). Everything else works without it.
- **PostgreSQL** - not needed for anything in this guide. The one place it's used
  (`ThreatContextStore`, `ModuleRegistry`) already fails gracefully (logs an error, keeps
  running) if the database isn't reachable, so you can ignore those log lines.

If `mvn` or `java` says "command not found", they're either not installed or not on your
`PATH` yet. On Ubuntu:
```bash
sudo apt update
sudo apt install openjdk-17-jdk maven -y
```
Then open a new terminal (or run `source ~/.bashrc`) and re-check with `java -version` /
`mvn -version`.

**Important repo convention:** this codebase's `WorkflowEngine` and `ThreatContextStore` build
with a classpath string that uses `;` as the separator in its README (that's the Windows form -
the same repo was also developed on Windows). **On Linux/Ubuntu you must use `:` instead of `;`
everywhere you see a classpath.** Every command in this guide already uses `:` - just don't
copy classpath syntax from the root `README.md` verbatim, it's written for both OSes with a
comment, not always the Linux form.

---

## Tier 1: Fast checks - nothing to install, ~5 minutes

### 1.1 Does everything compile?

This alone catches most possible mistakes.

```bash
cd "$REPO"
mvn compile
```

**What success looks like:** near the bottom, you'll see:
```
[INFO] Reactor Summary for NIS1 Thesis - SOAR Framework 1.0-SNAPSHOT:
[INFO]
[INFO] NIS1 Thesis - SOAR Framework ....................... SUCCESS
[INFO] nis-thesis-sdk ..................................... SUCCESS
[INFO] User Defined Modules ............................... SUCCESS
[INFO] Module Registry Lifecycle Manager .................. SUCCESS
[INFO] BUILD SUCCESS
```
If you see `BUILD FAILURE` instead, scroll up to the first `[ERROR]` line - that's the actual
problem, everything after it is noise.

That covers 3 of the 5 Java projects (they're all managed by Maven together). The other two,
`WorkflowEngine` and `ThreatContextStore`, use a different, simpler build method (plain `javac`,
not Maven) - that's a deliberate repo convention, not a mistake. Check those too:

```bash
cd "$REPO/WorkflowEngine"
javac -cp "lib/*:target/classes" -d target/classes src/main/java/com/yourorg/workflow/*.java
```
```bash
cd "$REPO/ThreatContextStore"
javac -cp "lib/*:target/classes" -d target/classes src/main/java/com/yourorg/middleware/*.java
```

**What success looks like:** no output at all. `javac` only prints something when there's an
error or warning - silence means it worked.

### 1.2 Run the automated test suite

This is the most important check - it's a set of 35 automated tests (there were zero before this
work started) that verify the code actually behaves correctly, not just that it compiles.

```bash
cd "$REPO"
mvn test
```

**What success looks like:**
```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0     <- nis-thesis-sdk
...
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0    <- user-defined-modules
...
[INFO] BUILD SUCCESS
```
35 tests total, 0 failures. If a number under "Failures" or "Errors" is anything other than 0,
Maven prints the failing test's name and a stack trace right above the summary - that tells you
exactly which check broke and why.

**What these 35 tests actually check**, in plain English:
- Can an `Event` (the SDK's generic message wrapper) be turned into JSON text and back into an
  object without losing data? (6 tests)
- Same question for the alert data used by Suricata, Maltrail, Fail2ban, and Sysmon (8 tests) -
  specifically making sure the JSON field names come out exactly right (e.g. `source_ip`, not
  `sourceIp`), since a typo there would silently break alert matching downstream.
- If any of the four modules receives garbage input (malformed JSON/log line, or input that's
  missing required fields like the source IP), does it correctly log a warning and drop the
  message instead of crashing? (roughly half the suite - one set of these tests per module)
- If a module receives a *well-formed* event, does it correctly figure out severity, category,
  and threat score? (checks the full mapping logic, including Sysmon's ransomware-command
  detection and Fail2ban's jail-based scoring)
- One test also caught and documents a real, pre-existing bug: the plain way this codebase
  serializes JSON can't actually handle timestamps correctly on this version of Java. The test
  proves that failure happens, and also proves the *correct* way (already available as a
  library dependency, just never wired up) fixes it.

### 1.3 Spot-check the documentation/schema fixes

These were simple text fixes, so "testing" them just means reading the file and confirming it
says what it should now.

```bash
cat "$REPO/ThreatContextStore/schema.sql"
```
Should show `CREATE TABLE alerts` - **not** `wazuh_alerts`.

```bash
grep -A3 "Running Unit Tests" "$REPO/README.md"
```
Should say no automated test suite existed *before this session* and point at the manual tester
utilities - not the old (false) claim that every module had its own test suite.

```bash
grep -B2 -A2 "topic exchange\|basicPublish" \
  "$REPO/nis-thesis-sdk/src/main/java/com/nis1/thesis/sdk/CoreSystemApi.java"
```
The Javadoc comment should describe a single named RabbitMQ queue, not a "topic exchange" (the
old comment described something the code never actually did).

### 1.4 Test the benchmark-summary script

No live system needed - this uses one tiny fake CSV file.

```bash
cd "$REPO"
mkdir -p scratch_test
cat > scratch_test/benchmark_run_test.csv << 'EOF'
traceId,stage,startTime,endTime,durationMs
alert-1,consume_deserialize,2026-07-30 14:00:01.000,2026-07-30 14:00:01.005,5
alert-1,workflow_load,2026-07-30 14:00:01.005,2026-07-30 14:00:01.050,45
alert-1,policy_match,2026-07-30 14:00:01.050,2026-07-30 14:00:01.060,10
alert-1,command_dispatch,2026-07-30 14:00:01.060,2026-07-30 14:00:01.075,15
alert-1,registry_route_dispatch,2026-07-30 14:00:01.075,2026-07-30 14:00:01.090,15
EOF

python3 scripts/summarize_benchmark.py scratch_test/benchmark_run_test.csv
rm -rf scratch_test
```

**What success looks like:** a table showing each of the 5 stages with a duration, then an
"end-to-end SDK time" section showing `90.0` (5+45+10+15+15) as the total for `alert-1`, with
"Complete traces: 1" and "Incomplete traces: 0".

---

## Tier 2: Verify a workflow matches, still without RabbitMQ

This proves a workflow YAML file is written correctly and would actually fire on a given alert -
checked directly against the matching engine, without needing a live message broker.

There's a permanent, supported tool for this now: `WorkflowDryRunTool.java` (lives alongside the
existing `WorkflowTester.java` in `WorkflowEngine/`). It replaced an earlier throwaway-`.java`-file
trick that had to be reinvented from scratch each time a new source's workflow needed checking -
see `SDK_USABILITY_AUDIT.md`'s Progressive Evaluation dimension for why that was worth fixing.

Build it once:
```bash
cd "$REPO/WorkflowEngine"
javac -cp "lib/*:target/classes" -d target/classes WorkflowDryRunTool.java
```

Then check any sample alert JSON against any workflow directory:
```bash
java -cp "lib/*:target/classes" WorkflowDryRunTool workflows/ransomware sample_alerts/maltrail_ransomware_alert.json
java -cp "lib/*:target/classes" WorkflowDryRunTool workflows/ransomware sample_alerts/sysmon_ransomware_alert.json
```

Two ready-to-use sample alerts already ship in `WorkflowEngine/sample_alerts/` - copy one and
edit its `payload` to try your own alert shape against any workflow you're writing.

**What success looks like:** the tool prints how many workflows matched and their names, e.g.:
```
=== RESULT ===
2 workflow(s) matched:
  - Maltrail High-Severity Ransomware Trail -> SDN Isolation (trigger event_type: alerts.network.maltrail)
  - ODL Ransomware Detection & Prevention Workflow (trigger event_type: )
```
If nothing matches, the tool prints a hint about what to check (event_type spelling, condition
field names/casing/operators).

---

## Tier 3: Full live pipeline test (needs RabbitMQ)

This is the real end-to-end test: a simulated Maltrail alert goes in over UDP, and a mitigation
command comes out the other side. It requires RabbitMQ actually running.

### 3.1 Start RabbitMQ

**Option A - native Ubuntu package (simplest, no Docker needed):**
```bash
sudo apt install rabbitmq-server -y
sudo systemctl enable --now rabbitmq-server
sudo rabbitmqctl add_user user password
sudo rabbitmqctl set_user_tags user administrator
sudo rabbitmqctl set_permissions -p / user ".*" ".*" ".*"
```

**Option B - Docker, if you have it installed:**
```bash
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
docker exec rabbitmq rabbitmqctl add_user user password
docker exec rabbitmq rabbitmqctl set_user_tags user administrator
docker exec rabbitmq rabbitmqctl set_permissions -p / user ".*" ".*" ".*"
```

(The username/password `user`/`password` matches what's already in this repo's config files -
don't change it unless you also update every `config.properties` / `*.properties` file.)

**Check it's actually up** before continuing:
```bash
nc -zv localhost 5672
```
Should print something like `Connection to localhost 5672 port [tcp/amqp] succeeded!`. If `nc`
isn't installed, `sudo apt install netcat-openbsd -y` first, or just try starting WorkflowEngine
in step 3.2 and see if it connects.

### 3.2 Start the Java processes

You need **five separate terminal tabs/windows** left open (they're long-lived processes, like
servers, not one-off commands). In each one, `cd "$REPO"` (or re-export `REPO` if it's a fresh
shell) before running its command. `NotificationModule` needs no separate terminal - it's
embedded and starts automatically inside ModuleRegistry (Terminal 1), the same way
`OpenDaylightModule` does.

**Terminal 1 - ModuleRegistry** (routes commands to modules, hosts the embedded
`NotificationModule`, `OpenDaylightModule`, `SuricataHttpModule`, and `ZeekHttpModule`):
```bash
cd "$REPO/ModuleRegistryLifecycleManager"
java -cp "target/classes:lib/*:../nis-thesis-sdk/target/classes:../user-defined-modules/target/classes:../user-defined-modules/*" com.yourorg.registry.ModuleRegistryMain
```
Wait for: `✅ ModuleRegistryAndLifecycleManager is running`. You'll also see a red
`❌ Failed to load modules from database` line - that's expected and harmless, it's just
Postgres being unreachable (see "What you need" above). You should also see
`[SdkModuleHost] Initialized module: Notification Module` in the startup log.

**Note on Suricata/Zeek:** unlike Maltrail/Fail2ban/Sysmon below, Suricata and Zeek do **not**
get their own terminal - `SuricataHttpModule`/`ZeekHttpModule` are embedded modules that start
automatically here in Terminal 1, each running its own tiny HTTP server (port 8090
`/suricata/alerts`, port 8091 `/zeek/notices`). In the real deployment those ports are fed by
`ids_http_forwarder.py` running on the separate VM that hosts Suricata/Zeek/OVS; for this guide,
`simulate_all_sources.py --source suricata`/`--source zeek` POSTs directly to those same
endpoints instead, once Terminal 1 is up.

**Terminal 2 - WorkflowEngine** (matches alerts against workflow YAML files):
```bash
cd "$REPO/WorkflowEngine"
java -cp "target/classes:lib/*" com.yourorg.workflow.WorkflowEngineMain
```
Wait for: `⏳ Waiting for alerts from queue: workflow_queue`.

**Terminal 3 - MaltrailModule** (listens for UDP events, publishes alerts):
```bash
cd "$REPO/user-defined-modules"
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*:../nis-thesis-sdk/target/classes" com.nis1.thesis.udm.MaltrailModule
```
Wait for: `✅ Maltrail UDP listener bound to port 8481`.

**Terminal 4 - Fail2banModule** (tails a simulated fail2ban.log, publishes alerts):
```bash
cd "$REPO/user-defined-modules"
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*:../nis-thesis-sdk/target/classes" com.nis1.thesis.udm.Fail2banModule
```
Wait for: `✅ Monitoring started from position:`. This module creates
`user-defined-modules/simulated_logs/fail2ban.log` itself if it doesn't already exist - no real
fail2ban install needed.

**Terminal 5 - SysmonModule** (listens for UDP Sysmon-shaped JSON, publishes alerts):
```bash
cd "$REPO/user-defined-modules"
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*:../nis-thesis-sdk/target/classes" com.nis1.thesis.udm.SysmonModule
```
Wait for: `✅ Sysmon UDP listener bound to port 8482`.

> **Classpath note:** all three commands above need `../nis-thesis-sdk/target/classes` explicitly -
> `AlertEnvelopeBuilder` (used by every one of these modules to build its alert envelope) lives in
> `nis-thesis-sdk`, which is only a Maven *compile-time* dependency here, never copied into
> `user-defined-modules/target/classes` or into `ModuleRegistryLifecycleManager/lib/`. Omitting it
> doesn't fail loudly: the module starts, binds its port/file-tail fine, and even successfully
> receives and parses events - it only dies (silently - a bare `NoClassDefFoundError`, an `Error`
> not an `Exception`, thrown from a background thread whose `ExecutorService.submit(...)` result is
> never checked) the first time it actually tries to build and publish an alert. Symptom: the
> module's own debug/receive logging looks totally healthy, but nothing ever reaches
> `workflow_queue` and every event after the first is silently dropped, because the listener thread
> that would have processed it is already dead.

If any of these fail immediately with a connection error, RabbitMQ isn't reachable - go back to
3.1.

> **Shortcut:** `$REPO/start-all.sh` automates starting ThreatContextStore, WorkflowEngine,
> ModuleRegistry (with its embedded modules), **and now MaltrailModule/Fail2banModule/
> SysmonModule too**, plus the web dashboard - all in one command, each redirected to
> `/dev/null` so it runs quietly in the background (Ctrl+C stops everything together). It still
> assumes RabbitMQ is already running - step 3.1 still applies first. Only use the manual
> Terminals 1-5 above instead if you want to see each module's own console output directly (e.g.
> to confirm a specific `✅ ... bound to port ...` startup line), since `start-all.sh` hides that
> output by design.

### 3.3 Send simulated events from all five sources

One script covers every source - in a **sixth** terminal:
```bash
cd "$REPO"
python3 scripts/simulate_all_sources.py --source all
```
Or trigger just one at a time: `--source maltrail`, `--source fail2ban`, `--source sysmon`,
`--source suricata`, or `--source zeek`. The last two POST directly to `SuricataHttpModule`/
`ZeekHttpModule`'s HTTP endpoints already running inside Terminal 1 (defaults
`http://localhost:8090/suricata/alerts` and `http://localhost:8091/zeek/notices` need no flags
normally - override with `--suricata-http-url`/`--zeek-http-url` only if Terminal 1 is running
somewhere other than localhost).

**What success looks like**, watching the terminals from step 3.2:

*Maltrail and Sysmon-ransomware paths (SDN isolation / notification):*
- **Terminal 3 (MaltrailModule):**
  `📤 Published Maltrail alert: ransomware [Severity: high] ... (trail: 203.0.113.9)`
- **Terminal 5 (SysmonModule):**
  `📤 Published Sysmon alert: Ransomware pre-encryption command detected: ... [Severity: critical]`
- **Terminal 2 (WorkflowEngine):** for Maltrail, `✅ Found 1 matching specific workflow(s)` then
  `Isolate host flagged by Maltrail ransomware trail` and
  `📤 Published command to workflow_response_queue`. For Sysmon, the matching workflow name is
  `Notify security team of Sysmon ransomware indicator`.
- **Terminal 1 (ModuleRegistry):** for Maltrail, `🎯 Routing command: INITIATE_MITIGATION` (then
  likely "No module found"/"module offline" since there's no real SDN controller connected - the
  important part, alert -> matched -> command generated, already happened). For Sysmon, you
  should see the command routed to the embedded module, followed by a boxed `🔔 NOTIFICATION`
  block printed directly in this terminal - proof the embedded `NotificationModule` pattern
  works end to end.

*Fail2ban path (notification):*
- **Terminal 4 (Fail2banModule):** `📤 Published Fail2ban alert: SSH brute-force (jail: sshd) ...`
- **Terminal 2 (WorkflowEngine):** matches `Fail2ban High-Severity Ban -> Notification`.
- **Terminal 1 (ModuleRegistry):** another `🔔 NOTIFICATION` block, this time about the
  Fail2ban ban.

If an alert doesn't show up in Terminal 2 within a couple seconds, check the corresponding
module's terminal actually printed its "Published ... alert" line first - if it didn't, the
simulated input isn't reaching that module (for UDP sources, check nothing else is using the
port, e.g. `sudo ss -tulpn | grep 8481` for Maltrail or `8482` for Sysmon; for Fail2ban, confirm
`user-defined-modules/simulated_logs/fail2ban.log` is the same path both the module and the
simulator script are using).

### 3.4 Check the performance-timing data this run produced

While those terminals are running, every alert that flows through also gets timed. Check for a
CSV file that appeared automatically:

```bash
ls "$REPO/WorkflowEngine/benchmark_output/"
ls "$REPO/ModuleRegistryLifecycleManager/benchmark_output/"
```

Each should contain a file named like `benchmark_run_20260725.csv`. Summarize both together:

```bash
python3 scripts/summarize_benchmark.py \
  "$REPO/WorkflowEngine/benchmark_output" \
  "$REPO/ModuleRegistryLifecycleManager/benchmark_output"
```

This is real, measured timing data for how long each internal processing step took for the
alert(s) you sent - this is the actual output the "SDK-only stage timing" work (Task 1) was
built to produce. Send a few more test events (repeat step 3.3) before running the summary if
you want more than one data point.

### 3.5 Shut everything down

Go to each of the three terminals from 3.2 and press `Ctrl+C`. If you started RabbitMQ via the
native package, you can leave it running (it's a system service) or stop it with
`sudo systemctl stop rabbitmq-server`. If you used Docker:
```bash
docker stop rabbitmq
docker rm rabbitmq
```

---

## Quick reference - what proves what

| Change | How to verify it | Tier |
|---|---|---|
| Table name fix (`wazuh_alerts` -> `alerts`) | Read `schema.sql` | 1.3 |
| `CoreSystemApi` Javadoc rewrite | Read the file | 1.3 |
| Dead timing-log removal | Read `WorkflowQueueListener.java` around the old line 141-159 (gone) | 1.3 |
| README test-suite claim fix | Read `README.md` | 1.3 |
| `StageTimer` + 5-stage instrumentation | Live run + check `benchmark_output/*.csv` exists | 3.4 |
| `summarize_benchmark.py` | Run against a sample or real CSV | 1.4 / 3.4 |
| `MaltrailModule` / `MaltrailAlertData` | Compiles + JUnit tests + (optionally) live UDP send | 1.1, 1.2, 3.3 |
| `Fail2banModule` / `Fail2banAlertData` | Compiles + JUnit tests + (optionally) live log-tail | 1.1, 1.2, 3.3 |
| `SysmonModule` / `SysmonAlertData` | Compiles + JUnit tests + (optionally) live UDP send | 1.1, 1.2, 3.3 |
| `NotificationModule` (embedded pattern) | Live run - watch for the `🔔 NOTIFICATION` block in ModuleRegistry's terminal | 3.3 |
| `maltrail_ransomware_isolate.yml` / `fail2ban_brute_force_notify.yml` / `sysmon_ransomware_notify.yml` workflows | Offline match check, or live run | 2 / 3.3 |
| Zero core-file changes for Maltrail/Fail2ban/Sysmon | `git status` shows no edits to `CoreSystemApi.java`/`WorkflowMatcher.java`/`OpenDaylightModule.java` from these additions | - |
| `simulate_all_sources.py` | Run with `--source all` or one source at a time | 3.3 |
| JUnit test suite (35 tests) | `mvn test` | 1.2 |
| `SDK_USABILITY_AUDIT.md` | Read it - no test needed, it's an analysis document, not code | - |

---

## Troubleshooting

- **`mvn: command not found` / `java: command not found`** - see "What you need before
  starting" above; install via `apt`, then open a new terminal.
- **`javac` prints nothing** - that's success, not a hang. `javac` is silent when it works.
- **A Java process exits immediately with a RabbitMQ/connection error** - RabbitMQ isn't running
  or isn't reachable on `localhost:5672`. Check with `nc -zv localhost 5672`.
- **"Failed to load modules from database" / "Failed to save module to database"** - expected
  and harmless in this guide; it just means PostgreSQL isn't reachable (only used for optional
  persistence, everything else still works over RabbitMQ).
- **`mvn test` fails with a specific test name** - scroll up from the failure to find the actual
  assertion that failed; the test names describe what they check (e.g.
  `alertMissingRequiredFieldsIsLoggedAndDropped`).
- **Permission denied running `rabbitmqctl`** - prefix with `sudo`, as shown above.
- **Port 8481 (Maltrail) or 8482 (Sysmon) already in use** - check what's using it with
  `sudo ss -tulpn | grep 8481` (or `8482`); either free it or change `maltrail.udp_port` /
  `sysmon.udp_port` in the corresponding `user-defined-modules/config/*.properties` file (and
  pass `--maltrail-port`/`--sysmon-port` to `simulate_all_sources.py` to match).
- **Fail2ban events never arrive** - `Fail2banModule` tails whatever `fail2ban.log_path` points
  at in `user-defined-modules/config/fail2ban-module.properties` (default:
  `simulated_logs/fail2ban.log`, relative to wherever the module was launched from - i.e.
  `user-defined-modules/`). Make sure `simulate_all_sources.py --source fail2ban` is writing to
  that exact same path (`--fail2ban-log-path`, relative to wherever *you* run the script from).
- **`simulate_all_sources.py --source suricata`/`--source zeek` prints "FAILED to reach ..."** -
  Terminal 1 (ModuleRegistry) isn't running yet, or isn't reachable on port 8090/8091 from
  wherever you're running the script. These two sources are HTTP-based (`SuricataHttpModule`/
  `ZeekHttpModule`'s own embedded HTTP servers, started inside ModuleRegistry, not a separate
  process) - unlike Maltrail/Fail2ban/Sysmon, appending to a local file or sending raw UDP does
  nothing for these two; a real Suricata/Zeek deployment reaches these same endpoints via
  `ids_http_forwarder.py` running on a separate machine, not by writing to a local log file on
  this one.
- **You copied the project over from Windows and file paths/line endings look weird** - shouldn't
  affect anything in this guide (Java/Maven/Python all handle CRLF line endings in text files
  fine), but if a `.sh` script itself fails with `bad interpreter` or `\r` errors, run
  `dos2unix start-all.sh` (or `sed -i 's/\r$//' start-all.sh`) to fix its line endings.
