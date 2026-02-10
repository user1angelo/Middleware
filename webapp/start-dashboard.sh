#!/bin/bash

echo "╔════════════════════════════════════════════════╗"
echo "║  Starting Middleware Dashboard                ║"
echo "╚════════════════════════════════════════════════╝"
echo ""

# Get the script directory (webapp/)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MIDDLEWARE_DIR="$(dirname "$SCRIPT_DIR")"

# Check if in correct directory
if [ ! -d "backend" ] || [ ! -d "frontend" ]; then
    echo "❌ Error: Please run this script from the webapp/ directory"
    exit 1
fi

# Check if dependencies are installed
if [ ! -d "backend/node_modules" ]; then
    echo "📦 Installing backend dependencies..."
    cd backend && npm install && cd ..
fi

if [ ! -d "frontend/node_modules" ]; then
    echo "📦 Installing frontend dependencies..."
    cd frontend && npm install && cd ..
fi

# Create logs directory
mkdir -p logs

echo ""
echo "🚀 Starting services..."
echo ""
echo "Backend will run on: http://localhost:3001"
echo "Frontend will open at: http://localhost:3000"
echo "SuricataModule and ZeekModule will also start"
echo ""
echo "Press CTRL+C to stop all services"
echo ""

# Cleanup function to kill all background processes
cleanup() {
    echo ""
    echo "🛑 Stopping all services..."
    kill $BACKEND_PID $FRONTEND_PID $SURICATA_PID $ZEEK_PID 2>/dev/null
    wait $BACKEND_PID $FRONTEND_PID $SURICATA_PID $ZEEK_PID 2>/dev/null
    echo "✅ Dashboard stopped"
    exit 0
}
trap cleanup SIGINT SIGTERM

# Start backend in background
cd backend
npm start &
BACKEND_PID=$!

# Wait a bit for backend to start
sleep 2

# Start frontend
cd ../frontend
npm start &
FRONTEND_PID=$!

# Start SuricataModule
cd "$MIDDLEWARE_DIR/user-defined-modules"
mvn exec:java -Dexec.mainClass="com.nis1.thesis.udm.SuricataModule" &
SURICATA_PID=$!

# Start ZeekModule
mvn exec:java -Dexec.mainClass="com.nis1.thesis.udm.ZeekModule" &
ZEEK_PID=$!

echo ""
echo "🟢 All services started"
echo "   Backend PID: $BACKEND_PID"
echo "   Frontend PID: $FRONTEND_PID"
echo "   SuricataModule PID: $SURICATA_PID"
echo "   ZeekModule PID: $ZEEK_PID"
echo ""

# Wait for any process to exit
wait $BACKEND_PID $FRONTEND_PID $SURICATA_PID $ZEEK_PID

cleanup

