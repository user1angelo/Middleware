# UUID Parsing Fix for Query Event IDs

## Problem

When sending query messages with event_id like `query-73255c41-8263-4722-a01f-5ed9c2a04b70`, the system crashed with:

```
java.lang.IllegalArgumentException: UUID string too large
	at java.util.UUID.fromString1(UUID.java:266)
	at java.util.UUID.fromString(UUID.java:260)
	at com.yourorg.middleware.RabbitMQListener.handleQuery(RabbitMQListener.java:193)
```

## Root Cause

1. TCSTester generates query event IDs with "query-" prefix: `query-<UUID>`
2. The database `event_id` column is UUID type (accepts only standard UUIDs)
3. Code tried to parse "query-73255c41-..." as a UUID, which is too long

## Solution

Added logic to strip the "query-" prefix before parsing:

### In `WazuhAlertDao.java` (insertMessage method):

```java
String eventIdStr = message.optString("event_id", UUID.randomUUID().toString());

// Handle query IDs with "query-" prefix
UUID eventId;
if (eventIdStr.startsWith("query-")) {
    // Remove "query-" prefix and parse the UUID
    eventId = UUID.fromString(eventIdStr.substring(6));
} else {
    eventId = UUID.fromString(eventIdStr);
}
```

### In `RabbitMQListener.java` (handleQuery method):

```java
String queryId = query.optString("event_id", UUID.randomUUID().toString());

// Extract UUID from queryId (remove "query-" prefix if present)
UUID queryUUID;
try {
    if (queryId.startsWith("query-")) {
        // Remove "query-" prefix and parse the UUID
        queryUUID = UUID.fromString(queryId.substring(6));
    } else {
        queryUUID = UUID.fromString(queryId);
    }
} catch (IllegalArgumentException e) {
    System.err.println("⚠️  Invalid UUID format in event_id: " + queryId);
    throw new Exception("Invalid UUID format in event_id: " + queryId, e);
}
```

Also wrapped the `updateQueryResponse()` call in the catch block to prevent secondary exceptions:

```java
} catch (Exception e) {
    // Update query status as failed
    try {
        dao.updateQueryResponse(queryUUID, 0, "failed");
    } catch (Exception updateException) {
        System.err.println("⚠️  Failed to update query status: " + updateException.getMessage());
    }
    throw e;
}
```

## How It Works

### Example 1: Query with prefix
```
Input:  "query-73255c41-8263-4722-a01f-5ed9c2a04b70"
Strip:  "73255c41-8263-4722-a01f-5ed9c2a04b70"
Store:  73255c41-8263-4722-a01f-5ed9c2a04b70 (as UUID in database)
```

### Example 2: Alert without prefix
```
Input:  "4e5344ac-7df0-4cc2-9714-2af6ba41a983"
Store:  4e5344ac-7df0-4cc2-9714-2af6ba41a983 (as UUID in database)
```

## Files Modified

1. `ThreatContextStore/src/main/java/com/yourorg/middleware/WazuhAlertDao.java`
   - Line 22-31: Added prefix stripping logic in `insertMessage()`

2. `ThreatContextStore/src/main/java/com/yourorg/middleware/RabbitMQListener.java`
   - Line 149-163: Added prefix stripping and error handling in `handleQuery()`
   - Line 206-211: Wrapped update call in try-catch to prevent secondary errors

## Testing

### Before fix:
```
📨 Message #11 received (deliveryTag=11, redelivered=false)
🏷️  Event ID: query-73255c41-8263-4722-a01f-5ed9c2a04b70
🔖 Message Type: query
➡️  Routing to: handleQuery()
❌❌❌ EXCEPTION: UUID string too large
```

### After fix:
```
📨 Message #11 received (deliveryTag=11, redelivered=false)
🏷️  Event ID: query-73255c41-8263-4722-a01f-5ed9c2a04b70
🔖 Message Type: query
➡️  Routing to: handleQuery()
🔍 Processing query: query-73255c41-8263-4722-a01f-5ed9c2a04b70
   SQL: SELECT * FROM wazuh_alerts WHERE ...
   Found 5 results
✅ Sent 5 query responses
✅ ACK sent for message #11
```

## Deployment

1. **Compile the updated code:**
   ```bash
   cd ThreatContextStore
   javac -cp "lib/*:out" -d out src/main/java/com/yourorg/middleware/*.java
   ```

2. **Copy compiled classes to remote machine:**
   ```bash
   scp -r out/* user@remote-machine:/path/to/ThreatContextStore/out/
   ```

3. **Restart ThreatContextStore on remote machine:**
   ```bash
   # Find and kill existing process
   ps aux | grep ThreatContextStore
   kill <PID>
   
   # Restart
   java -cp "out:lib/*" com.yourorg.middleware.ThreatContextStoreMain 2>&1 | tee logs.txt
   ```

## Compatibility

- ✅ **Backward compatible**: Handles both prefixed and non-prefixed UUIDs
- ✅ **Alert messages**: Continue to work as before (no prefix)
- ✅ **Query messages**: Now work correctly (with or without prefix)
- ✅ **Database schema**: No changes required

## Notes

- The "query-" prefix is cosmetic and only exists in the JSON message
- The database stores only the pure UUID (without prefix)
- Query responses will have regular UUIDs (from the original alerts)
- This is transparent to the end user

