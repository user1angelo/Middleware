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
    kill $TCS_PID $WE_PID $MR_PID $MALTRAIL_PID $FAIL2BAN_PID $SYSMON_PID $BACKEND_PID $FRONTEND_PID 2>/dev/null
    wait $TCS_PID $WE_PID $MR_PID $MALTRAIL_PID $FAIL2BAN_PID $SYSMON_PID $BACKEND_PID $FRONTEND_PID 2>/dev/null
    echo "✅ System safely shut down."
    exit 0
}
trap cleanup SIGINT SIGTERM

echo "🚀 [1/8] Starting Threat Context Store..."
cd "$SCRIPT_DIR/ThreatContextStore"
java -cp "target/classes:lib/*" com.yourorg.middleware.ThreatContextStoreMain > /dev/null 2>&1 &
TCS_PID=$!

echo "🚀 [2/8] Starting Workflow Engine..."
cd "$SCRIPT_DIR/WorkflowEngine"
java -cp "target/classes:lib/*" com.yourorg.workflow.WorkflowEngineMain > /dev/null 2>&1 &
WE_PID=$!

echo "🚀 [3/8] Starting Module Registry (Loading OpenDaylightModule, SuricataHttpModule, ZeekHttpModule, NotificationModule)..."
cd "$SCRIPT_DIR/ModuleRegistryLifecycleManager"
java -cp "target/classes:lib/*:../nis-thesis-sdk/target/classes:../user-defined-modules/target/classes:../user-defined-modules/*" com.yourorg.registry.ModuleRegistryMain > /dev/null 2>&1 &
MR_PID=$!

# The three modules below are standalone processes (their own main(), their own RabbitMQ
# connection) - they are NOT loaded by Module Registry above, unlike the embedded modules in
# step 3. Each registers itself with Module Registry over RabbitMQ once it's up.

echo "🚀 [4/8] Starting Maltrail Module (UDP listener, port 8481)..."
cd "$SCRIPT_DIR/user-defined-modules"
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*" com.nis1.thesis.udm.MaltrailModule > /dev/null 2>&1 &
MALTRAIL_PID=$!

echo "🚀 [5/8] Starting Fail2ban Module (tails simulated_logs/fail2ban.log)..."
cd "$SCRIPT_DIR/user-defined-modules"
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*" com.nis1.thesis.udm.Fail2banModule > /dev/null 2>&1 &
FAIL2BAN_PID=$!

echo "🚀 [6/8] Starting Sysmon Module (UDP listener, port 8482)..."
cd "$SCRIPT_DIR/user-defined-modules"
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*" com.nis1.thesis.udm.SysmonModule > /dev/null 2>&1 &
SYSMON_PID=$!

echo "🚀 [7/8] Starting Web Dashboard Backend..."
cd "$SCRIPT_DIR/webapp/backend"
npm install --silent > /dev/null 2>&1
npm start > /dev/null 2>&1 &
BACKEND_PID=$!

sleep 2

echo "🚀 [8/8] Starting Web Dashboard Frontend..."
cd "$SCRIPT_DIR/webapp/frontend"
npm install --silent > /dev/null 2>&1
PORT=3000 npm start &
FRONTEND_PID=$!

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
