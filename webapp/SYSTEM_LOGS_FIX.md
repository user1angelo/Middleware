# System Logs Fix

## Problem
The System Logs page was not displaying log messages from running processes (ThreatContextStore, ModuleRegistry, WorkflowEngine).

## Root Cause
The frontend React component had undefined array issues when trying to append logs to state for process keys that weren't initialized or dynamically added.

## Changes Made

### 1. Frontend: `/webapp/frontend/src/pages/Logs.js`

**Fixed undefined array issues:**
- Added fallback for `prev[data.process]` when it doesn't exist yet
- Added fallback for `prev.rabbitmq` array
- Added safeguards when rendering logs for tabs that may not have data yet

**Before:**
```javascript
[data.process]: [...prev[data.process], newLog].slice(-1000)
```

**After:**
```javascript
[data.process]: [...(prev[data.process] || []), newLog].slice(-1000)
```

This allows logs to be collected even for process keys that weren't pre-initialized in the state.

### 2. Backend: `/webapp/backend/services/processManager.js`

**Enhanced debugging:**
- Added byte count logging for stdout/stderr
- Added explicit "Broadcasting to WebSocket..." messages
- Improved error messages when logService is null
- Better visibility into what's being captured vs what's being sent

### 3. Backend: `/webapp/backend/services/logService.js`

**Improved broadcast logging:**
- Shows number of bytes being broadcast
- Shows number of connected clients
- Better error messages with emoji indicators
- More visibility into Socket.IO state

## How to Test

### 1. Start the dashboard
```bash
cd webapp
./start-dashboard.sh
```

### 2. Open the dashboard
Navigate to: http://localhost:3000

### 3. Start a process
- Go to "Control Panel"
- Click "Start" on ThreatContextStore
- Watch the backend console for debug messages

### 4. View logs
- Go to "System Logs"
- Select "ThreatContextStore" tab
- You should see real-time logs appearing

### 5. Backend Console Output
You should see messages like:
```
[threatContextStore] stdout (1024 bytes): Starting ThreatContextStore...
[threatContextStore] Broadcasting to WebSocket...
✉️  Broadcasting log for 'threatContextStore' (1024 bytes) to 1 client(s)
```

### 6. Frontend Browser Console
Open browser DevTools and check for:
```
Received process-log: {process: "threatContextStore", message: "...", timestamp: "..."}
```

## Expected Behavior

1. **Process starts** → Backend captures stdout/stderr
2. **Backend logs** show byte counts and broadcast confirmations
3. **WebSocket emits** `process-log` events with proper structure
4. **Frontend receives** and appends to appropriate log array
5. **UI updates** in real-time with log messages
6. **No errors** in browser or backend console

## Debugging Steps

If logs still don't appear:

1. **Check WebSocket connection:**
   - Look for "Connected" badge in System Logs page
   - Check browser console for `WebSocket connected!`

2. **Check backend is receiving output:**
   - Look for `[processKey] stdout (N bytes)` messages
   - Verify logStream is writing to file (check `webapp/backend/logs/`)

3. **Check logService is initialized:**
   - Should NOT see "ERROR: logService is NULL" messages
   - Should see "Broadcasting to WebSocket..." messages

4. **Check Socket.IO:**
   - Should see "Broadcasting log for 'X' to N client(s)"
   - N should be > 0 if frontend is connected

5. **Check frontend state:**
   - Add `console.log(logs)` in Logs.js to see state updates
   - Verify arrays are being populated

## Files Modified

- `webapp/frontend/src/pages/Logs.js` - Fixed undefined array issues
- `webapp/backend/services/processManager.js` - Enhanced logging
- `webapp/backend/services/logService.js` - Improved broadcast debugging

## Testing Checklist

- [ ] Backend starts without errors
- [ ] Frontend connects to WebSocket
- [ ] Starting a process shows output in backend console
- [ ] Logs appear in System Logs page in real-time
- [ ] All three process tabs work (ThreatContextStore, ModuleRegistry, WorkflowEngine)
- [ ] RabbitMQ logs also appear (if enabled)
- [ ] No JavaScript errors in browser console
- [ ] Log files are created in `webapp/backend/logs/`
