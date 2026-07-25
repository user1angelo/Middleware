# Testing Guide - How to Verify Everything That Changed

This guide assumes you know nothing about this codebase. It walks through checking all the
work from this session, starting with the easiest checks (nothing extra to install) and
working up to a full live end-to-end test (needs RabbitMQ running).

**You do not need to do every tier.** Tier 1 alone proves the code is correct. Tiers 2-4 prove
it actually works when wired up to a real message broker, which is nice to see but optional.

Every command below is written for **Windows PowerShell**, run from the repo root
(`C:\Users\keanl\Documents\GitHub\Middleware`), unless a step says to `cd` somewhere else.

---

## What you need before starting

- **Java 17+** and **Maven** - already installed and working (confirmed during this session).
- **Python 3** - only needed for two small test scripts (Tier 1.4 and Tier 3).
- **RabbitMQ** - only needed for Tier 3 (the full live test). Everything else works without it.
- **PostgreSQL** - not needed for anything in this guide. The one place it's used
  (`ThreatContextStore`, `ModuleRegistry`) already fails gracefully (logs an error, keeps
  running) if the database isn't reachable, so you can ignore those log lines.

One thing every `mvn` command needs on this machine: PowerShell doesn't pick up Maven's
location automatically in a fresh window. If you ever see `mvn: The term 'mvn' is not
recognized`, run this first (once per PowerShell window) and then retry:

```powershell
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
```

---

## Tier 1: Fast checks - nothing to install, ~5 minutes

### 1.1 Does everything compile?

This alone catches most possible mistakes.

```powershell
mvn -f "C:\Users\keanl\Documents\GitHub\Middleware\pom.xml" compile
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

```powershell
cd "C:\Users\keanl\Documents\GitHub\Middleware\WorkflowEngine"
javac -cp "lib/*;target/classes" -d target/classes src/main/java/com/yourorg/workflow/*.java
```
```powershell
cd "C:\Users\keanl\Documents\GitHub\Middleware\ThreatContextStore"
javac -cp "lib/*;target/classes" -d target/classes src/main/java/com/yourorg/middleware/*.java
```

**What success looks like:** no output at all. `javac` only prints something when there's an
error or warning - silence means it worked.

### 1.2 Run the automated test suite

This is the most important check - it's a set of 21 automated tests (added this session; there
were zero before) that verify the code actually behaves correctly, not just that it compiles.

```powershell
cd "C:\Users\keanl\Documents\GitHub\Middleware"
mvn test
```

**What success looks like:**
```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0     <- nis-thesis-sdk
...
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0    <- user-defined-modules
...
[INFO] BUILD SUCCESS
```
21 tests total, 0 failures. If a number under "Failures" or "Errors" is anything other than 0,
Maven prints the failing test's name and a stack trace right above the summary - that tells you
exactly which check broke and why.

**What these 21 tests actually check**, in plain English:
- Can an `Event` (the SDK's generic message wrapper) be turned into JSON text and back into an
  object without losing data? (6 tests)
- Same question for the alert data used by Suricata and by the new Maltrail module (4 tests) -
  specifically making sure the JSON field names come out exactly right (e.g. `source_ip`, not
  `sourceIp`), since a typo there would silently break alert matching downstream.
- If the Suricata or Maltrail module receives garbage input (malformed JSON, or JSON that's
  missing required fields like the source IP), does it correctly log a warning and drop the
  message instead of crashing? (8 tests)
- If Maltrail sends a *well-formed* event, does the module correctly figure out its severity,
  category, and threat score? (1 test - checks the full mapping logic)
- One test also caught and documents a real, pre-existing bug: the plain way this code base
  serializes JSON can't actually handle timestamps correctly on this version of Java. The test
  proves that failure happens, and also proves the *correct* way (already available as a
  library dependency, just never wired up) fixes it.

### 1.3 Spot-check the documentation/schema fixes

These were simple text fixes, so "testing" them just means reading the file and confirming it
says what it should now.

```powershell
Get-Content "C:\Users\keanl\Documents\GitHub\Middleware\ThreatContextStore\schema.sql"
```
Should show `CREATE TABLE alerts` - **not** `wazuh_alerts`.

```powershell
Get-Content "C:\Users\keanl\Documents\GitHub\Middleware\README.md" | Select-String -Context 2,2 "Running Unit Tests"
```
Should say no automated test suite existed *before this session* and point at the manual tester
utilities - not the old (false) claim that every module had its own test suite.

```powershell
Get-Content "C:\Users\keanl\Documents\GitHub\Middleware\nis-thesis-sdk\src\main\java\com\nis1\thesis\sdk\CoreSystemApi.java" | Select-String -Context 1,1 "topic exchange|basicPublish"
```
The Javadoc comment should describe a single named RabbitMQ queue, not a "topic exchange" (the
old comment described something the code never actually did).

### 1.4 Test the benchmark-summary script

No live system needed - this uses two tiny fake CSV files.

```powershell
cd "C:\Users\keanl\Documents\GitHub\Middleware"
New-Item -ItemType Directory -Force -Path scratch_test | Out-Null
@"
traceId,stage,startEpochMs,endEpochMs,durationMs
alert-1,consume_deserialize,1000,1005,5
alert-1,workflow_load,1005,1050,45
alert-1,policy_match,1050,1060,10
alert-1,command_dispatch,1060,1075,15
alert-1,registry_route_dispatch,1075,1090,15
"@ | Set-Content scratch_test\benchmark_run_test.csv

python scripts\summarize_benchmark.py scratch_test\benchmark_run_test.csv
Remove-Item -Recurse -Force scratch_test
```

**What success looks like:** a table showing each of the 5 stages with a duration, then an
"end-to-end SDK time" section showing `90.0` (5+45+10+15+15) as the total for `alert-1`, with
"Complete traces: 1" and "Incomplete traces: 0".

---

## Tier 2: Verify the Maltrail workflow matches, still without RabbitMQ

This proves the new `maltrail_ransomware_isolate.yml` workflow file is written correctly and
would actually fire on a real Maltrail alert - checked directly against the matching engine,
without needing a live message broker.

Save this as `WorkflowEngine\WorkflowMatchCheck.java` (temporary file, not part of the real
codebase - delete it when you're done):

```java
import com.yourorg.workflow.*;
import org.json.JSONObject;
import java.util.List;

public class WorkflowMatchCheck {
    public static void main(String[] args) throws Exception {
        WorkflowLoader loader = new WorkflowLoader();
        WorkflowMatcher matcher = new WorkflowMatcher();
        List<Workflow> workflows = loader.loadWorkflows("workflows/ransomware");

        JSONObject alert = new JSONObject();
        alert.put("message_type", "alert");
        alert.put("event_id", "test-001");
        alert.put("timestamp", java.time.Instant.now().toString());
        alert.put("event_type", "alerts.network.maltrail");
        alert.put("source_module", "Maltrail UDM");

        JSONObject payload = new JSONObject();
        payload.put("severity", "high");
        payload.put("alert_type", "ransomware");
        payload.put("source_ip", "10.0.0.55");
        payload.put("signature", "ransomware");
        alert.put("payload", payload);

        List<Workflow> matches = matcher.findMatchingWorkflows(alert, workflows);
        boolean matched = matches.stream().anyMatch(w -> w.getName().contains("Maltrail"));
        System.out.println(matched ? "PASS - Maltrail workflow matched" : "FAIL - did not match");
    }
}
```

Then run:
```powershell
cd "C:\Users\keanl\Documents\GitHub\Middleware\WorkflowEngine"
javac -cp "lib/*;target/classes" -d target/classes WorkflowMatchCheck.java
java -cp "lib/*;target/classes" WorkflowMatchCheck
Remove-Item WorkflowMatchCheck.java, target\classes\WorkflowMatchCheck.class
```

**What success looks like:** `PASS - Maltrail workflow matched`.

---

## Tier 3: Full live pipeline test (needs RabbitMQ)

This is the real end-to-end test: a simulated Maltrail alert goes in over UDP, and a mitigation
command comes out the other side. It requires RabbitMQ actually running.

### 3.1 Start RabbitMQ

The easiest way, if you have Docker Desktop installed:
```powershell
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
docker exec rabbitmq rabbitmqctl add_user user password
docker exec rabbitmq rabbitmqctl set_user_tags user administrator
docker exec rabbitmq rabbitmqctl set_permissions -p / user ".*" ".*" ".*"
```
(The username/password `user`/`password` matches what's already in this repo's config files -
don't change it unless you also update every `config.properties` / `*.properties` file.)

Don't have Docker? Install RabbitMQ natively from https://www.rabbitmq.com/docs/install-windows
instead, then run the same three `rabbitmqctl` commands (drop the `docker exec rabbitmq` prefix).

**Check it's actually up** before continuing:
```powershell
Test-NetConnection -ComputerName localhost -Port 5672
```
`TcpTestSucceeded` should say `True`.

### 3.2 Start the three Java processes

Open **three separate PowerShell windows** (each one needs to stay open and running - they're
long-lived processes, like servers). In each, refresh PATH first if needed (see the top of this
guide).

**Window 1 - ModuleRegistry** (routes commands to modules):
```powershell
cd "C:\Users\keanl\Documents\GitHub\Middleware\ModuleRegistryLifecycleManager"
java -cp "target/classes;lib/*;../nis-thesis-sdk/target/classes;../user-defined-modules/target/classes;../user-defined-modules/*" com.yourorg.registry.ModuleRegistryMain
```
Wait for: `✅ ModuleRegistryAndLifecycleManager is running`. You'll also see a red
`❌ Failed to load modules from database` line - that's expected and harmless, it's just
Postgres being unreachable (see "What you need" above).

**Window 2 - WorkflowEngine** (matches alerts against workflow YAML files):
```powershell
cd "C:\Users\keanl\Documents\GitHub\Middleware\WorkflowEngine"
java -cp "target/classes;lib/*" com.yourorg.workflow.WorkflowEngineMain
```
Wait for: `⏳ Waiting for alerts from queue: workflow_queue`.

**Window 3 - MaltrailModule** (the new module - listens for UDP events, publishes alerts):
```powershell
cd "C:\Users\keanl\Documents\GitHub\Middleware\user-defined-modules"
java -cp "target/classes;../ModuleRegistryLifecycleManager/lib/*" com.nis1.thesis.udm.MaltrailModule
```
Wait for: `✅ Maltrail UDP listener bound to port 8481`.

If any of these three fail immediately with a connection error, RabbitMQ isn't reachable - go
back to 3.1.

### 3.3 Send a simulated Maltrail alert

In a **fourth** PowerShell window:
```powershell
cd "C:\Users\keanl\Documents\GitHub\Middleware"
python scripts\send_test_maltrail_event.py
```

**What success looks like**, watching the three windows from step 3.2:
- **Window 3 (MaltrailModule):** a line like
  `📤 Published Maltrail alert: ransomware [Severity: high] ... (trail: 203.0.113.9)`
- **Window 2 (WorkflowEngine):** the alert arrives, and further down you should see
  `✅ Found 1 matching specific workflow(s)` followed by
  `Isolate host flagged by Maltrail ransomware trail` (the step name from the new workflow file)
  and `📤 Published command to workflow_response_queue`.
- **Window 1 (ModuleRegistry):** a line like `🎯 Routing command: INITIATE_MITIGATION` -
  this is the point where the system would actually isolate the host on a real network.
  It's expected to then say something like "No module found with capability" or "module
  offline" and stop there, since there's no real SDN switch/OpenDaylight controller connected
  in this test - the important part (alert → matched → mitigation command generated) already
  happened successfully by this point.

If you don't see the alert show up in Window 2 within a couple seconds, double check Window 3
actually printed the "Published Maltrail alert" line first - if it didn't, the UDP packet from
step 3.3 isn't reaching the module (check nothing else is using port 8481).

### 3.4 Check the performance-timing data this run produced

While those windows are running, every alert that flows through also gets timed. Check for a
CSV file that appeared automatically:

```powershell
Get-ChildItem "C:\Users\keanl\Documents\GitHub\Middleware\WorkflowEngine\benchmark_output\"
Get-ChildItem "C:\Users\keanl\Documents\GitHub\Middleware\ModuleRegistryLifecycleManager\benchmark_output\"
```

Each should contain a file named like `benchmark_run_20260725.csv`. Summarize both together:

```powershell
python scripts\summarize_benchmark.py `
  "C:\Users\keanl\Documents\GitHub\Middleware\WorkflowEngine\benchmark_output" `
  "C:\Users\keanl\Documents\GitHub\Middleware\ModuleRegistryLifecycleManager\benchmark_output"
```

This is real, measured timing data for how long each internal processing step took for the
alert(s) you sent - this is the actual output the "SDK-only stage timing" work (Task 1) was
built to produce. Send a few more test events (repeat step 3.3) before running the summary if
you want more than one data point.

### 3.5 Shut everything down

Go to each of the three windows from 3.2 and press `Ctrl+C`. If you started RabbitMQ via Docker:
```powershell
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
| `maltrail_ransomware_isolate.yml` workflow | Offline match check, or live run | 2 / 3.3 |
| Zero core-file changes for Maltrail | `git status` shows no edits to `CoreSystemApi.java`/`WorkflowMatcher.java`/`OpenDaylightModule.java` from this addition | - |
| JUnit test suite (21 tests) | `mvn test` | 1.2 |

---

## Troubleshooting

- **`mvn: The term 'mvn' is not recognized`** - run the PATH-refresh command at the top of this
  guide, then retry the same command.
- **`javac` prints nothing** - that's success, not a hang. `javac` is silent when it works.
- **A Java process exits immediately with a RabbitMQ/connection error** - RabbitMQ isn't running
  or isn't reachable on `localhost:5672`. Check `Test-NetConnection -ComputerName localhost -Port 5672`.
- **"Failed to load modules from database" / "Failed to save module to database"** - expected
  and harmless in this guide; it just means PostgreSQL isn't reachable (only used for optional
  persistence, everything else still works over RabbitMQ).
- **`mvn test` fails with a specific test name** - scroll up from the failure to find the actual
  assertion that failed; the test names describe what they check (e.g.
  `alertMissingRequiredFieldsIsLoggedAndDropped`).
- **Port 8481 already in use** - something else on your machine is using that UDP port; either
  free it or change `maltrail.udp_port` in
  `user-defined-modules\config\maltrail-module.properties` (and pass `--port` to
  `send_test_maltrail_event.py` to match).
