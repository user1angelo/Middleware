#!/bin/bash
# Quick diagnostic script to check RabbitMQ queue status

RABBITMQ_HOST="192.168.86.76"
RABBITMQ_PORT="15672"
RABBITMQ_USER="guest"
RABBITMQ_PASS="guest"

echo "═══════════════════════════════════════════════════"
echo "  RabbitMQ Queue Status Check"
echo "═══════════════════════════════════════════════════"
echo ""
echo "Checking queues on: $RABBITMQ_HOST:$RABBITMQ_PORT"
echo ""

# Check if curl is available
if ! command -v curl &> /dev/null; then
    echo "❌ curl is not installed. Please install it to use this script."
    exit 1
fi

# Try to get queue information
response=$(curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASS" \
    "http://$RABBITMQ_HOST:$RABBITMQ_PORT/api/queues" 2>&1)

if [ $? -ne 0 ]; then
    echo "❌ Failed to connect to RabbitMQ management API"
    echo "   Make sure the management plugin is enabled:"
    echo "   rabbitmq-plugins enable rabbitmq_management"
    exit 1
fi

# Parse and display queue information
echo "📊 Queue Information:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "$response" | grep -o '"name":"[^"]*"' | sed 's/"name":"//g' | sed 's/"//g' | while read queue_name; do
    queue_info=$(echo "$response" | grep -o "\"name\":\"$queue_name\"[^}]*}")
    
    messages=$(echo "$queue_info" | grep -o '"messages":[0-9]*' | grep -o '[0-9]*')
    messages_ready=$(echo "$queue_info" | grep -o '"messages_ready":[0-9]*' | grep -o '[0-9]*')
    messages_unack=$(echo "$queue_info" | grep -o '"messages_unacknowledged":[0-9]*' | grep -o '[0-9]*')
    consumers=$(echo "$queue_info" | grep -o '"consumers":[0-9]*' | grep -o '[0-9]*')
    
    echo ""
    echo "Queue: $queue_name"
    echo "  Total messages:       ${messages:-0}"
    echo "  Ready:                ${messages_ready:-0}"
    echo "  Unacknowledged:       ${messages_unack:-0}"
    echo "  Active consumers:     ${consumers:-0}"
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "💡 Tips:"
echo "   - If 'Ready' count is high: Messages are waiting to be consumed"
echo "   - If 'Unacknowledged' count is high: Messages are being processed"
echo "   - If 'Active consumers' is 0: No listener is connected"
echo ""

