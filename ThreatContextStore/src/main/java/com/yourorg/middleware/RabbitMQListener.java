package com.yourorg.middleware;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Dedicated RabbitMQ listener that consumes messages and processes them based on message_type
 */
public class RabbitMQListener {
    private static final String QUEUE_NAME = ConfigLoader.getRabbitMqQueueName();
    private static final String QUERY_RESPONSE_QUEUE = ConfigLoader.getRabbitMqQueryResponseQueueName();
    private static final String RABBITMQ_HOST = ConfigLoader.getRabbitMqHost();
    private static final int RABBITMQ_PORT = ConfigLoader.getRabbitMqPort();
    private static final String RABBITMQ_USER = ConfigLoader.getRabbitMqUser();
    private static final String RABBITMQ_PASSWORD = ConfigLoader.getRabbitMqPassword();

    private final WazuhAlertDao dao;
    private Connection connection;
    private Channel channel;
    private int messageCounter = 0;

    public RabbitMQListener() {
        this.dao = new WazuhAlertDao();
    }

    /**
     * Start listening to RabbitMQ queue
     */
    public void start() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);

        connection = factory.newConnection();
        channel = connection.createChannel();

        // Declare queues (idempotent - creates only if doesn't exist)
        channel.queueDeclare(QUEUE_NAME, true, false, false, null);
        channel.queueDeclare(QUERY_RESPONSE_QUEUE, true, false, false, null);
        
        // Set prefetch to allow processing multiple messages concurrently
        // This prevents one failed message from blocking all others
        channel.basicQos(10);

        System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST);
        System.out.println("⏳ Waiting for messages from queue: " + QUEUE_NAME);
        System.out.println("   Press CTRL+C to exit.\n");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            messageCounter++;
            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            boolean redelivered = delivery.getEnvelope().isRedeliver();
            
            System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("📨 Message #" + messageCounter + " received (deliveryTag=" + deliveryTag + ", redelivered=" + redelivered + ")");
            
            try {
                String message = new String(delivery.getBody(), "UTF-8");
                System.out.println("📄 Raw message length: " + message.length() + " bytes");
                
                JSONObject json = new JSONObject(message);
                String eventId = json.optString("event_id", "unknown");
                String messageType = json.optString("message_type", "alert");
                
                System.out.println("🏷️  Event ID: " + eventId);
                System.out.println("🔖 Message Type: " + messageType);
                
                switch (messageType) {
                    case "alert":
                        System.out.println("➡️  Routing to: handleAlert()");
                        handleAlert(json);
                        break;
                    case "query":
                        System.out.println("➡️  Routing to: handleQuery()");
                        handleQuery(json);
                        break;
                    case "query_response":
                        System.out.println("➡️  Routing to: handleQueryResponse()");
                        handleQueryResponse(json);
                        break;
                    default:
                        System.err.println("⚠️  Unknown message_type: " + messageType);
                }
                
                // Acknowledge message
                channel.basicAck(deliveryTag, false);
                System.out.println("✅ ACK sent for message #" + messageCounter + " (deliveryTag=" + deliveryTag + ")");
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                
            } catch (Exception e) {
                System.err.println("\n❌❌❌ EXCEPTION in message #" + messageCounter + " ❌❌❌");
                System.err.println("Error message: " + e.getMessage());
                System.err.println("Exception type: " + e.getClass().getName());
                System.err.println("\nFull stack trace:");
                e.printStackTrace();
                
                if (redelivered) {
                    // Message already failed once - don't requeue again to avoid infinite loop
                    System.err.println("\n⚠️  This message ALREADY FAILED ONCE (redelivered=true)");
                    System.err.println("⚠️  DISCARDING message #" + messageCounter + " (deliveryTag=" + deliveryTag + ") to prevent infinite loop");
                    channel.basicNack(deliveryTag, false, false);
                    System.err.println("🗑️  NACK sent (requeue=false) - message discarded");
                } else {
                    // First failure - give it one more try by requeuing
                    System.err.println("\n⚠️  This is the FIRST FAILURE (redelivered=false)");
                    System.err.println("⚠️  REQUEUING message #" + messageCounter + " (deliveryTag=" + deliveryTag + ") for one retry");
                    channel.basicNack(deliveryTag, false, true);
                    System.err.println("🔄 NACK sent (requeue=true) - message will be retried");
                }
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            }
        };

        // Start consuming
        channel.basicConsume(QUEUE_NAME, false, deliverCallback, consumerTag -> {
            System.out.println("Consumer cancelled: " + consumerTag);
        });

        // Keep the listener running
        keepAlive();
    }

    /**
     * Handle alert message: store in database
     */
    private void handleAlert(JSONObject alert) throws Exception {
        System.out.println("   🔄 Calling dao.insertMessage()...");
        dao.insertMessage(alert);
        System.out.println("   ✓ Database insert successful");
        
        JSONObject payload = alert.optJSONObject("payload");
        String severity = payload != null ? payload.optString("severity", "unknown") : "unknown";
        System.out.println("   🔥 Alert stored successfully | Event ID: " + alert.optString("event_id") + " | Severity: " + severity);
    }

    /**
     * Handle query message: execute query, send results back to RabbitMQ, update query status
     */
    private void handleQuery(JSONObject query) throws Exception {
        String queryId = query.optString("event_id", UUID.randomUUID().toString());
        System.out.println("🔍 Processing query: " + queryId);
        
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
        
        try {
            // Store query in database
            dao.insertMessage(query);
            
            // Translate query to SQL
            QueryTranslator.QueryResult queryResult = QueryTranslator.translate(query);
            System.out.println("   SQL: " + queryResult.getSql());
            
            // Execute query
            List<JSONObject> results = dao.executeQuery(queryResult.getSql(), queryResult.getParameters());
            System.out.println("   Found " + results.size() + " results");
            
            // Create output directory
            String outputPath = ConfigLoader.getQueryResponsesPath();
            File outputDir = new File(outputPath);
            System.out.println("   📁 Query responses directory: " + outputDir.getAbsolutePath());
            
            if (!outputDir.exists()) {
                System.out.println("   📁 Directory doesn't exist, creating...");
                boolean created = outputDir.mkdirs();
                if (created) {
                    System.out.println("   ✓ Directory created successfully");
                } else {
                    System.err.println("   ⚠️  Failed to create directory!");
                }
            } else {
                System.out.println("   ✓ Directory already exists");
            }
            
            // Send each result as query_response to RabbitMQ and save to file
            int successCount = 0;
            for (JSONObject result : results) {
                // Change message_type to query_response
                result.put("message_type", "query_response");
                
                // Send to query_response_queue
                channel.basicPublish("", QUERY_RESPONSE_QUEUE, null, result.toString().getBytes("UTF-8"));
                System.out.println("   📤 Sent to query_response_queue");
                
                // Write to file
                String eventId = result.getString("event_id");
                File outFile = new File(outputDir, eventId + ".json");
                System.out.println("   💾 Saving to file: " + outFile.getAbsolutePath());
                
                try (FileWriter writer = new FileWriter(outFile)) {
                    writer.write(result.toString(4)); // pretty-print
                    writer.flush();
                }
                
                if (outFile.exists()) {
                    System.out.println("   ✓ File saved successfully (" + outFile.length() + " bytes)");
                } else {
                    System.err.println("   ⚠️  File was not created!");
                }
                
                successCount++;
            }
            
            // Update query with response summary
            dao.updateQueryResponse(queryUUID, successCount, "success");
            System.out.println("✅ Sent " + successCount + " query responses");
            
        } catch (Exception e) {
            // Update query status as failed
            try {
                dao.updateQueryResponse(queryUUID, 0, "failed");
            } catch (Exception updateException) {
                System.err.println("⚠️  Failed to update query status: " + updateException.getMessage());
            }
            throw e;
        }
    }

    /**
     * Handle query_response message: just log it (not stored in database)
     */
    private void handleQueryResponse(JSONObject queryResponse) {
        String eventId = queryResponse.optString("event_id", "unknown");
        System.out.println("📥 Received query_response: " + eventId + " (not stored)");
    }

    /**
     * Keep the application running to continue consuming messages
     */
    private void keepAlive() {
        try {
            synchronized (this) {
                this.wait();
            }
        } catch (InterruptedException e) {
            System.out.println("Listener interrupted, shutting down...");
            shutdown();
        }
    }

    /**
     * Gracefully shutdown the listener
     */
    public void shutdown() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
            System.out.println("✅ RabbitMQ connection closed gracefully.");
        } catch (Exception e) {
            System.err.println("Error during shutdown:");
            e.printStackTrace();
        }
    }

    /**
     * Main method to run the listener
     */
    public static void main(String[] args) {
        RabbitMQListener listener = new RabbitMQListener();
        
        // Add shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Shutdown signal received...");
            listener.shutdown();
        }));

        try {
            listener.start();
        } catch (Exception e) {
            System.err.println("❌ Fatal error starting listener:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}