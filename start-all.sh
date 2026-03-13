#!/bin/bash
set -euo pipefail

echo "╔════════════════════════════════════════════════╗"
echo "║  Starting FULL Middleware Security System      ║"
echo "╚════════════════════════════════════════════════╝"
echo ""

# Get the script directory (Middleware root)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

compile_java_module() {
    local module_dir="$1"
    local classpath="$2"
    local source_root="$module_dir/src/main/java"
    local sources=()

    if [ ! -d "$source_root" ]; then
        echo "⚠️  Skipping compile for $module_dir (no src/main/java)"
        return 0
    fi

    mkdir -p "$module_dir/target/classes"

    while IFS= read -r -d '' file; do
        sources+=("$file")
    done < <(find "$source_root" -name "*.java" -print0)

    if [ "${#sources[@]}" -eq 0 ]; then
        echo "⚠️  Skipping compile for $module_dir (no Java sources found)"
        return 0
    fi

    (
        cd "$module_dir"
        javac -cp "$classpath" -d target/classes "${sources[@]}"
    )
}

wait_for_pid_alive() {
    local pid="$1"
    local name="$2"
    local timeout_s="${3:-10}"
    local waited=0

    while [ "$waited" -lt "$timeout_s" ]; do
        if kill -0 "$pid" 2>/dev/null; then
            echo "✅ $name is running (pid=$pid)"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done

    echo "❌ $name failed to stay running (pid=$pid)"
    return 1
}

wait_for_http_endpoint() {
    local name="$1"
    local url="$2"
    local timeout_s="${3:-15}"
    local waited=0

    if ! command -v curl >/dev/null 2>&1; then
        echo "⚠️  curl not found; skipping HTTP health check for $name"
        return 0
    fi

    while [ "$waited" -lt "$timeout_s" ]; do
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" "$url" || true)
        if [ "$code" = "200" ] || [ "$code" = "405" ]; then
            echo "✅ $name HTTP endpoint is reachable ($url, status=$code)"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done

    echo "❌ $name HTTP endpoint did not become reachable: $url"
    return 1
}

echo "🛑 Stopping any old Java or Node processes..."
killall -9 java 2>/dev/null || true
killall -9 node 2>/dev/null || true

echo "🔧 Pre-compiling Java components to avoid stale classes..."
echo "   • Maven modules: SDK, UDM, ModuleRegistryLifecycleManager"
mvn -q -f "$SCRIPT_DIR/pom.xml" compile

echo "   • ThreatContextStore (standalone module)"
compile_java_module "$SCRIPT_DIR/ThreatContextStore" "lib/*:target/classes"

echo "   • WorkflowEngine (standalone module)"
compile_java_module "$SCRIPT_DIR/WorkflowEngine" "lib/*:target/classes"

# Cleanup function to kill all background processes if user presses Ctrl+C
cleanup() {
    echo ""
    echo "🛑 Stopping all services..."
    kill $TCS_PID $WE_PID $MR_PID $BACKEND_PID $FRONTEND_PID 2>/dev/null
    wait $TCS_PID $WE_PID $MR_PID $BACKEND_PID $FRONTEND_PID 2>/dev/null
    echo "✅ System safely shut down."
    exit 0
}
trap cleanup SIGINT SIGTERM

echo "🚀 [1/5] Starting Threat Context Store..."
cd "$SCRIPT_DIR/ThreatContextStore"
java -cp "target/classes:lib/*" com.yourorg.middleware.ThreatContextStoreMain &
TCS_PID=$!

echo "🚀 [2/5] Starting Workflow Engine..."
cd "$SCRIPT_DIR/WorkflowEngine"
java -cp "target/classes:lib/*" com.yourorg.workflow.WorkflowEngineMain &
WE_PID=$!

echo "🚀 [3/5] Starting Module Registry (Loading ZeekHttpModule!)..."
cd "$SCRIPT_DIR/ModuleRegistryLifecycleManager"
java -cp "target/classes:lib/*:../nis-thesis-sdk/target/classes:../user-defined-modules/target/classes:../user-defined-modules/*" com.yourorg.registry.ModuleRegistryMain &
MR_PID=$!

echo "🩺 Running startup health checks for Java services..."
wait_for_pid_alive "$TCS_PID" "ThreatContextStore"
wait_for_pid_alive "$WE_PID" "WorkflowEngine"
wait_for_pid_alive "$MR_PID" "ModuleRegistry"
wait_for_http_endpoint "Suricata HTTP ingest" "http://127.0.0.1:8090/suricata/alerts"
wait_for_http_endpoint "Zeek HTTP ingest" "http://127.0.0.1:8091/zeek/notices"

echo "🚀 [4/5] Starting Web Dashboard Backend..."
cd "$SCRIPT_DIR/webapp/backend"
npm install --silent > /dev/null 2>&1
npm start &
BACKEND_PID=$!

sleep 2

echo "🚀 [5/5] Starting Web Dashboard Frontend..."
cd "$SCRIPT_DIR/webapp/frontend"
npm install --silent > /dev/null 2>&1
PORT=3000 npm start &
FRONTEND_PID=$!

echo "🩺 Running startup health checks for web services..."
wait_for_pid_alive "$BACKEND_PID" "Web backend"
wait_for_pid_alive "$FRONTEND_PID" "Web frontend"

echo ""
echo "🟢 ALL SYSTEMS ARE GO!"
echo "   Dashboard is opening at: http://localhost:3000"
echo "   (Do not click the 'Start' buttons in the UI, they are already running natively!)"
echo ""
echo "   Waiting for logs from your Python Forwarder..."
echo ""
echo "Press CTRL+C here to stop the entire stack."
echo ""

# Wait to keep terminal alive
wait
