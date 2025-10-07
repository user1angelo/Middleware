package com.yourorg.middleware;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.json.JSONObject;

public class AlertProcessor {
    private static final String QUEUE_NAME = ConfigLoader.getRabbitMqQueueName();

    public static void main(String[] args) throws Exception {

        // Use relative path from config
        File folder = new File(ConfigLoader.getMessagesPath());
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("Invalid folder path: " + folder.getAbsolutePath());
            return;
        }

        // Connect to RabbitMQ
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(ConfigLoader.getRabbitMqHost());
        factory.setPort(ConfigLoader.getRabbitMqPort());
        factory.setUsername(ConfigLoader.getRabbitMqUser());
        factory.setPassword(ConfigLoader.getRabbitMqPassword());

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            channel.queueDeclare(QUEUE_NAME, true, false, false, null);
            System.out.println("✅ Connected to RabbitMQ queue: " + QUEUE_NAME);

            List<File> files = Arrays.asList(folder.listFiles(f -> f.getName().endsWith(".json")));

            for (File file : files) {
                try {
                    String content = Files.readString(file.toPath());
                    JSONObject json = new JSONObject(content);

                    // Ensure message_type is present (default to "alert")
                    if (!json.has("message_type")) {
                        json.put("message_type", "alert");
                    }

                    channel.basicPublish("", QUEUE_NAME, null, json.toString().getBytes("UTF-8"));
                    System.out.println("✅ Sent " + file.getName() + " to RabbitMQ (type: " + json.getString("message_type") + ")");
                } catch (Exception e) {
                    System.out.println("⚠ Failed to send " + file.getName());
                    e.printStackTrace();
                }
            }
        }
    }
}
