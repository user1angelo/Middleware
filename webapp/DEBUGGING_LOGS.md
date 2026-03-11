# Debugging Logs Not Appearing

## Quick Test

1. **Restart the backend:**
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/webapp/backend
npm start
```

2. **Open the test page in your browser:**
```bash
# Open in browser:
file:///home/keyanluwi/Documents/GitHub/Middleware/webapp/test-websocket.html
```

3. **Check what you see:**
   - Should show "Connected" status
   - Should see "[Dashboard] WebSocket connection established" message immediately

## Step-by-Step Diagnosis

### Step 1: Check Backend Console

When you start the backend, you should see:
```
🚀 Server running on port 3001
📡 WebSocket ready for real-time logs
✅ Connected to RabbitMQ for log monitoring
  📡 Monitoring queue: alerts_queue
  📡 Monitoring queue: workflow_queue
  📡 Monitoring queue: query_response_queue
  📡 Monitoring queue: workflow_response_queue
```

If you don't see RabbitMQ connection, that's why RabbitMQ logs aren't showing.

### Step 2: Check Frontend Console

Open browser dev tools (F12) on the Logs page and check console:
```
Connecting to WebSocket...
WebSocket connected!
```

If you see "WebSocket disconnected" or errors, the WebSocket isn't connecting properly.

### Step 3: Start a Program and Watch Backend Console

Start ThreatContextStore from the Control Panel and watch the backend terminal:
```
Started ThreatContextStore with PID 12345
Broadcasting log for threatContextStore: ╔═══════════════...
Broadcasting log for threatContextStore: ║  ThreatConte...
```

If you DON'T see "Broadcasting log" messages, the process isn't outputting anything OR the capture isn't working.

### Step 4: Check if Process is Actually Running

In another terminal:
```bash
ps aux | grep java
```

Should show the Java processes running. If not, they failed to start.

### Step 5: Check Log Files

Log files are created here:
```bash
ls -lh /home/keyanluwi/Documents/GitHub/Middleware/webapp/logs/
```

If the files exist and have content, the process IS running and outputting, but the WebSocket isn't working.

## Common Issues & Fixes

### Issue 1: Backend Not Emitting Logs

**Symptom:** Backend shows "Started X with PID Y" but no "Broadcasting log" messages

**Fix:** The process might not be outputting to stdout. Check if you started the programs manually before starting from the dashboard. Stop them manually first:
```bash
pkill -f ThreatContextStoreMain
pkill -f ModuleRegistryMain
pkill -f WorkflowEngineMain
```

### Issue 2: WebSocket Not Connecting

**Symptom:** Frontend shows "Disconnected" status

**Possible causes:**
1. Backend not running on port 3001
2. CORS issue
3. Firewall blocking WebSocket

**Fix:**
```bash
# Check if backend is running
netstat -tlnp | grep 3001

# If not found, restart backend
cd webapp/backend
npm start
```

### Issue 3: RabbitMQ Connection Failed

**Symptom:** Backend doesn't show "✅ Connected to RabbitMQ"

**Fix:** Check RabbitMQ credentials in `backend/.env`:
```bash
RABBITMQ_HOST=192.168.1.8
RABBITMQ_PORT=5672
RABBITMQ_USER=user
RABBITMQ_PASSWORD=password
```

Test connection:
```bash
curl http://192.168.1.8:15672/api/overview -u user:password
```

### Issue 4: Process Started but No Output

**Symptom:** Process shows "running" in Control Panel but no logs

**Possible causes:**
1. Java program hasn't printed anything yet
2. Output is buffered
3. Program crashed immediately after starting

**Fix:** Check the log file directly:
```bash
tail -f /home/keyanluwi/Documents/GitHub/Middleware/webapp/logs/threatContextStore_*.log
```

If the file is empty, the Java process isn't outputting or crashed.

## Manual Test

Test if the programs work when started manually:

```bash
# Terminal 1 - Start ThreatContextStore manually
cd /home/keyanluwi/Documents/GitHub/Middleware/ThreatContextStore
java -cp "target/classes:lib/*" com.yourorg.middleware.ThreatContextStoreMain
```

Do you see output? If yes, the program works. If no, there's a problem with the Java program itself.

## Quick Fix: Add Test Endpoint

I've added a test message that's sent immediately when you connect to the Logs page. You should see:

```
[Dashboard] WebSocket connection established
```

in the ThreatContextStore tab as soon as you open the Logs page. If you don't see this, the WebSocket isn't connecting at all.

## What to Check Right Now

1. **Is the backend running?** Check terminal
2. **Open test-websocket.html** in browser - does it connect?
3. **Open browser dev console** on Logs page - any errors?
4. **Start a program** - does backend console show "Broadcasting log"?
5. **Check log files** - do they have content?

Let me know what you see at each step and I can pinpoint the exact issue!
