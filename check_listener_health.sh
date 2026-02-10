#!/bin/bash
# Check ThreatContextStore listener health
# Run this on the machine where ThreatContextStore is running

echo "═══════════════════════════════════════════════════"
echo "  ThreatContextStore Health Check"
echo "═══════════════════════════════════════════════════"
echo ""

# Check if Java process is running
echo "1. Checking if ThreatContextStore process is running..."
if ps aux | grep -i "ThreatContextStore" | grep -v grep > /dev/null; then
    echo "   ✅ Process is running"
    ps aux | grep -i "ThreatContextStore" | grep -v grep | awk '{print "      PID:", $2, "CPU:", $3"%", "MEM:", $4"%"}'
else
    echo "   ❌ Process is NOT running!"
    exit 1
fi

echo ""
echo "2. Checking RabbitMQ connectivity..."
if timeout 5 bash -c "cat < /dev/null > /dev/tcp/192.168.86.76/5672" 2>/dev/null; then
    echo "   ✅ Can connect to RabbitMQ (192.168.86.76:5672)"
else
    echo "   ❌ CANNOT connect to RabbitMQ (192.168.86.76:5672)"
    echo "      Network issue or RabbitMQ is down"
fi

echo ""
echo "3. Checking PostgreSQL connectivity..."
if timeout 5 bash -c "cat < /dev/null > /dev/tcp/localhost/5432" 2>/dev/null; then
    echo "   ✅ Can connect to PostgreSQL (localhost:5432)"
else
    echo "   ❌ CANNOT connect to PostgreSQL (localhost:5432)"
    echo "      Network issue or PostgreSQL is down"
fi

echo ""
echo "4. Checking log file (if exists)..."
if [ -f "logs.txt" ]; then
    echo "   ✅ Log file found: logs.txt"
    echo ""
    echo "   Last 20 lines:"
    echo "   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    tail -20 logs.txt | sed 's/^/   /'
    echo "   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    # Check for errors
    error_count=$(grep -c "❌" logs.txt 2>/dev/null || echo "0")
    echo "   Total errors in log: $error_count"
    
    if [ "$error_count" -gt 0 ]; then
        echo ""
        echo "   Last 5 errors:"
        grep "❌" logs.txt | tail -5 | sed 's/^/   /'
    fi
else
    echo "   ⚠️  No log file found"
    echo "      Start ThreatContextStore with: java ... 2>&1 | tee logs.txt"
fi

echo ""
echo "5. Checking RabbitMQ queue status..."
response=$(curl -s -u guest:guest http://192.168.86.76:15672/api/queues/%2F/alerts_queue 2>/dev/null)

if [ $? -eq 0 ] && [ ! -z "$response" ]; then
    consumers=$(echo "$response" | grep -o '"consumers":[0-9]*' | grep -o '[0-9]*')
    messages=$(echo "$response" | grep -o '"messages":[0-9]*' | grep -o '[0-9]*' | head -1)
    ready=$(echo "$response" | grep -o '"messages_ready":[0-9]*' | grep -o '[0-9]*')
    unack=$(echo "$response" | grep -o '"messages_unacknowledged":[0-9]*' | grep -o '[0-9]*')
    
    echo "   Queue: alerts_queue"
    echo "      Active consumers:     ${consumers:-0}"
    echo "      Total messages:       ${messages:-0}"
    echo "      Ready:                ${ready:-0}"
    echo "      Unacknowledged:       ${unack:-0}"
    echo ""
    
    if [ "${consumers:-0}" -eq 0 ]; then
        echo "   ❌ NO ACTIVE CONSUMERS!"
        echo "      This means ThreatContextStore is NOT listening to RabbitMQ"
        echo "      Even though the process is running, it's not consuming messages"
    else
        echo "   ✅ Listener is connected to RabbitMQ"
    fi
else
    echo "   ⚠️  Cannot query RabbitMQ API"
fi

echo ""
echo "═══════════════════════════════════════════════════"
echo "  Summary"
echo "═══════════════════════════════════════════════════"
echo ""
echo "If you see 'NO ACTIVE CONSUMERS' above:"
echo "  1. Check the last few lines of logs.txt for errors"
echo "  2. The listener thread may have crashed"
echo "  3. Try restarting ThreatContextStore"
echo ""
echo "To restart ThreatContextStore:"
echo "  1. Find PID: ps aux | grep ThreatContextStore"
echo "  2. Kill: kill <PID>"
echo "  3. Restart: java -cp \"out:lib/*\" com.yourorg.middleware.ThreatContextStoreMain 2>&1 | tee logs.txt"
echo ""

