# Middleware Context Guide (for future fix prompts)

This file is a quick operating context for debugging/fixing this repository efficiently.

## 1) Project Snapshot

- **Repo:** `Middleware`
- **Primary stack:** Java services + Node.js webapp backend/frontend
- **Core middleware services:**
  - `ThreatContextStore`
  - `WorkflowEngine`
  - `ModuleRegistryLifecycleManager`
- **Web UI:** `webapp` (`backend` + `frontend`)
- **User-defined Java modules:** `user-defined-modules`

## 2) Canonical Build/Runtime Convention (IMPORTANT)

Use this as the single source of truth:

- **Compiled Java classes:** `target/classes`
- **Java libs:** `lib/*`
- **SDK classes:** `nis-thesis-sdk/target/classes`
- **JAR outputs:** `target/*.jar`

Avoid relying on legacy `out/` paths unless explicitly needed for historical reasons.

## 3) Known Launch Paths

### `start-all.sh`
Launches Java services via `target/classes`:

- `ThreatContextStore`: `java -cp "target/classes:lib/*" com.yourorg.middleware.ThreatContextStoreMain`
- `WorkflowEngine`: `java -cp "target/classes:lib/*" com.yourorg.workflow.WorkflowEngineMain`
- `ModuleRegistry`: `java -cp "target/classes:lib/*" com.yourorg.registry.ModuleRegistryMain`

Also starts web backend/frontend.

### Webapp backend launcher
`webapp/backend/services/processManager.js` is standardized to `target/classes` for Java process startup and compile fallbacks.

## 4) Restart Meaning (for code changes)

When Java code changes, **restart services/app**, not the whole machine.

- Rebuild/recompile
- Stop running stack
- Start stack again

A machine reboot is **not** required.

## 5) Recommended Dev Flow for Java Fixes

From repo root:

```bash
cd "/home/gerome/Downloads/2-9-2026 testiso/Middleware"
mvn clean install
./start-all.sh
```

If changing user-defined modules, ensure they are rebuilt too (via project scripts or compile step used by `processManager`).

## 6) Fast Validation Checklist After a Fix

1. Confirm target class exists/updated under `target/classes/...`
2. Confirm service starts without classpath errors
3. Confirm webapp path still points to `target/classes`
4. Trigger a minimal end-to-end test event and verify expected behavior/logs

Useful quick checks:

```bash
# verify key artifacts
ls -l ThreatContextStore/target/classes/com/yourorg/middleware/ThreatContextStoreMain.class
ls -l WorkflowEngine/target/classes/com/yourorg/workflow/WorkflowEngineMain.class

# verify launcher/classpath references
grep -RIn "target/classes" start-all.sh webapp/backend/services/processManager.js
```

## 7) Troubleshooting Priorities

When diagnosing issues, check in this order:

1. **Wrong classpath/output dir** (`out` vs `target/classes` mismatch)
2. **Stale classes** (source changed but service not restarted)
3. **Missing module compilation** (especially user-defined modules)
4. **Missing dependencies in `lib/*`**
5. **Queue/listener behavior** (RabbitMQ loops, duplicate broadcasting)

## 8) Prompt Template for Future “Fix this” Requests

Copy and adapt this for better results:

```text
Context:
- Service: <ThreatContextStore|WorkflowEngine|ModuleRegistry|Webapp Backend|UDM>
- Symptom: <what is broken>
- Expected behavior: <what should happen>
- Recent changes: <files/commits>

Constraints:
- Keep runtime on target/classes
- Minimal, focused changes only
- Compile and validate after edits

Do:
1) Find root cause
2) Patch code/scripts/docs as needed
3) Run targeted validation
4) Summarize changed files + exact verification commands
```

## 9) Important Working Rules

- Prefer minimal, surgical changes in existing codebase.
- Fix root causes; avoid temporary workarounds where possible.
- If generated binaries are accidentally modified/tracked, keep source changes and clean generated artifacts before finalizing.
- Keep docs/scripts aligned with the actual runtime behavior.

---

Last updated: 2026-03-11
