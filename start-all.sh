#!/bin/bash

echo "╔════════════════════════════════════════════════╗"
echo "║  Starting FULL Middleware Security System      ║"
echo "╚════════════════════════════════════════════════╝"
echo ""

# Get the script directory (Middleware root)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "🛑 Stopping any old Java or Node processes..."
killall -9 java 2>/dev/null
killall -9 node 2>/dev/null

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
java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain > /dev/null 2>&1 &
TCS_PID=$!

echo "🚀 [2/5] Starting Workflow Engine..."
cd "$SCRIPT_DIR/WorkflowEngine"
java -cp "out:lib/*" com.yourorg.workflow.WorkflowEngineMain > /dev/null 2>&1 &
WE_PID=$!

echo "🚀 [3/5] Starting Module Registry (Loading ZeekHttpModule!)..."
cd "$SCRIPT_DIR/ModuleRegistryLifecycleManager"
java -cp "out:lib/*" com.yourorg.registry.ModuleRegistryMain > /dev/null 2>&1 &
MR_PID=$!

echo "🚀 [4/5] Starting Web Dashboard Backend..."
cd "$SCRIPT_DIR/webapp/backend"
npm install --silent > /dev/null 2>&1
npm start > /dev/null 2>&1 &
BACKEND_PID=$!

sleep 2

echo "🚀 [5/5] Starting Web Dashboard Frontend..."
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
