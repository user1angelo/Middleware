package com.yourorg.middleware;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import java.util.Iterator;

/**
 * Dedicated RabbitMQ listener that consumes alerts and stores them in PostgreSQL
 */
public class RabbitMQListener {
    private static final String QUEUE_NAME = "alerts_queue";
    private static final String RABBITMQ_HOST = "192.168.86.76";
    private static final int RABBITMQ_PORT = 5672;
    private static final String RABBITMQ_USER = "guest";
    private static final String RABBITMQ_PASSWORD = "guest";

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

        // Declare queue (idempotent - creates only if doesn't exist)
        channel.queueDeclare(QUEUE_NAME, true, false, false, null);
        
        // Set prefetch to process one message at a time
        channel.basicQos(1);

        System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST);
        System.out.println("⏳ Waiting for messages from queue: " + QUEUE_NAME);
        System.out.println("   Press CTRL+C to exit.\n");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            try {
                String message = new String(delivery.getBody(), "UTF-8");
                JSONObject json = new JSONObject(message);

                // Flatten nested payload if present
                flattenPayload(json);
                
                // Insert into PostgreSQL
                dao.insertAlert(json);

                System.out.println("🔥 Inserted alert: " + json.optString("rule_id", "unknown") 
                    + " | Severity: " + json.optString("severity", "unknown"));
                
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
     * Flatten nested "payload" JSON object into root level
     */
    private void flattenPayload(JSONObject json) {
        if (json.has("payload")) {
            JSONObject payload = json.getJSONObject("payload");
            Iterator<String> keys = payload.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                json.put(key, payload.get(key));
            }
            json.remove("payload");
        }
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