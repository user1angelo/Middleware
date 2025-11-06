#!/bin/bash

echo "╔════════════════════════════════════════════════╗"
echo "║  Starting Middleware Dashboard                ║"
echo "╚════════════════════════════════════════════════╝"
echo ""

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
echo ""
echo "Press CTRL+C to stop both services"
echo ""

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

# Wait for either process to exit
wait $BACKEND_PID $FRONTEND_PID

echo ""
echo "✅ Dashboard stopped"
