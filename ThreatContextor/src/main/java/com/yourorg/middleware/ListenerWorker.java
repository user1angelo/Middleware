package com.yourorg.middleware;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class ListenerWorker {

    private static final String QUEUE_NAME = "alerts_queue";
    private static final String RABBIT_HOST = "192.168.86.76";
    private static final int RABBIT_PORT = 5672;
    private static final String RABBIT_USER = "guest";
    private static final String RABBIT_PASS = "guest";

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
                JSONObject alert = new JSONObject(message);
                dao.insertAlert(alert); // insert into Postgres if not duplicate
                System.out.println("✅ Inserted alert: " + alert.optString("event_id"));
            } catch (Exception e) {
                System.err.println("⚠ Failed to insert alert: " + e.getMessage());
                e.printStackTrace();
            }
        };

        channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> { });
    }
}
