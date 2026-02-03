# Debugging "No such file or directory" Errors

If you encounter this error when starting modules from the Web App, check the following:

## 1. Check `.env` Location
The `.env` file **MUST** be in `webapp/backend/.env`.
We recently found it was in `webapp/backend/services/.env`, which is incorrect.
**Action:** Move it to `webapp/backend/.env` on your test machine.

## 2. Check Java Installation
The modules use the `java` command. If Java is not installed or not in your system PATH, you will get a "no such file or directory" error (referring to the `java` executable).
**Action:** Run `java -version` in a terminal.
- If it fails, install the JDK (Java Development Kit) 17 or higher.

## 3. Check Directory Paths
The `.env` file uses relative paths:
```ini
THREAT_CONTEXT_STORE_PATH=../../ThreatContextStore
MODULE_REGISTRY_PATH=../../ModuleRegistryLifecycleManager
...
```
These paths are relative to `webapp/backend`.
**Action:** Ensure your test machine has the exact same directory structure as the repository. The `webapp` folder should be a sibling of `ThreatContextStore`, `ModuleRegistryLifecycleManager`, etc.

## 4. Check Compilation
The processes expect a `out/` or `lib/` directory inside each module folder.
**Action:** directory check
- Does `Middleware/ThreatContextStore/out` exist?
- Does `Middleware/ModuleRegistryLifecycleManager/out` exist?
If not, you must compile the project first (or copy the compiled artifacts to the test machine).

## 5. Check Logs
Check the backend logs at `webapp/backend/logs/`.
Also check the browser console (F12) for detailed error messages from the API response.
