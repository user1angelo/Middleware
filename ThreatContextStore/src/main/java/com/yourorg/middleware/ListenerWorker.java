package com.yourorg.middleware;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class ListenerWorker {

    private static final String QUEUE_NAME = ConfigLoader.getRabbitMqQueueName();
    private static final String RABBIT_HOST = ConfigLoader.getRabbitMqHost();
    private static final int RABBIT_PORT = ConfigLoader.getRabbitMqPort();
    private static final String RABBIT_USER = ConfigLoader.getRabbitMqUser();
    private static final String RABBIT_PASS = ConfigLoader.getRabbitMqPassword();

    public static void main(String[] args) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT_HOST);
        factory.setPort(RABBIT_PORT);
        factory.setUsername(RABBIT_USER);
        factory.setPassword(RABBIT_PASS);

        com.rabbitmq.client.Connection rabbitConn = factory.newConnection();
        Channel channel = rabbitConn.createChannel();
        channel.queueDeclare(QUEUE_NAME, true, false, false, null);

        System.out.println("✅ Connected to RabbitMQ queue: " + QUEUE_NAME);
        System.out.println("🔔 Listening for incoming alerts...");

        WazuhAlertDao dao = new WazuhAlertDao();

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                JSONObject json = new JSONObject(message);
                String messageType = json.optString("message_type", "alert");
                
                // Only store alerts and queries (not query_response)
                if (!messageType.equals("query_response")) {
                    dao.insertMessage(json); // insert into Postgres if not duplicate
                    System.out.println("✅ Stored " + messageType + ": " + json.optString("event_id"));
                } else {
                    System.out.println("📥 Received query_response: " + json.optString("event_id") + " (not stored)");
                }
            } catch (Exception e) {
                System.err.println("⚠ Failed to process message: " + e.getMessage());
                e.printStackTrace();
            }
        };

        channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> { });
    }
}
