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
        
        // Set prefetch to process one message at a time
        channel.basicQos(1);

        System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST);
        System.out.println("⏳ Waiting for messages from queue: " + QUEUE_NAME);
        System.out.println("   Press CTRL+C to exit.\n");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            try {
                String message = new String(delivery.getBody(), "UTF-8");
                JSONObject json = new JSONObject(message);

                // Route based on message_type
                String messageType = json.optString("message_type", "alert");
                
                switch (messageType) {
                    case "alert":
                        handleAlert(json);
                        break;
                    case "query":
                        handleQuery(json);
                        break;
                    case "query_response":
                        handleQueryResponse(json);
                        break;
                    default:
                        System.err.println("⚠ Unknown message_type: " + messageType);
                }
                
                // Acknowledge message
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                
            } catch (Exception e) {
                System.err.println("❌ Failed to process message:");
                e.printStackTrace();
                
                // Negative acknowledge - requeue the message
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
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
        dao.insertMessage(alert);
        JSONObject payload = alert.optJSONObject("payload");
        String severity = payload != null ? payload.optString("severity", "unknown") : "unknown";
        System.out.println("🔥 Stored alert: " + alert.optString("event_id") + " | Severity: " + severity);
    }

    /**
     * Handle query message: execute query, send results back to RabbitMQ, update query status
     */
    private void handleQuery(JSONObject query) throws Exception {
        String queryId = query.optString("event_id", UUID.randomUUID().toString());
        System.out.println("🔍 Processing query: " + queryId);
        
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
            File outputDir = new File(ConfigLoader.getQueryResponsesPath());
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            // Send each result as query_response to RabbitMQ and save to file
            int successCount = 0;
            for (JSONObject result : results) {
                // Change message_type to query_response
                result.put("message_type", "query_response");
                
                // Send to query_response_queue
                channel.basicPublish("", QUERY_RESPONSE_QUEUE, null, result.toString().getBytes("UTF-8"));
                
                // Write to file
                String eventId = result.getString("event_id");
                File outFile = new File(outputDir, eventId + ".json");
                try (FileWriter writer = new FileWriter(outFile)) {
                    writer.write(result.toString(4)); // pretty-print
                }
                
                successCount++;
            }
            
            // Update query with response summary
            dao.updateQueryResponse(UUID.fromString(queryId), successCount, "success");
            System.out.println("✅ Sent " + successCount + " query responses");
            
        } catch (Exception e) {
            // Update query status as failed
            dao.updateQueryResponse(UUID.fromString(queryId), 0, "failed");
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