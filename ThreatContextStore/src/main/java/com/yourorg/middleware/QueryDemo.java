package com.yourorg.middleware;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import org.json.JSONObject;

public class QueryDemo {

    private static final String QUEUE_NAME = "alerts_queue";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java -cp ... QueryDemo <severity>");
            return;
        }

        String severity = args[0];
        WazuhAlertDao dao = new WazuhAlertDao();

        // Query alerts from PostgreSQL
        List<JSONObject> alerts = dao.queryBySeverityList(severity);

        if (alerts.isEmpty()) {
            System.out.println("No alerts found for severity = " + severity);
            return;
        }

        // Create output folder
        File outputDir = new File("output");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Setup RabbitMQ connection (optional)
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("192.168.86.76");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");

        Connection rabbitConn = null;
        Channel channel = null;

        try {
            rabbitConn = factory.newConnection();
            channel = rabbitConn.createChannel();
            channel.queueDeclare(QUEUE_NAME, true, false, false, null);
            System.out.println("✅ Connected to RabbitMQ queue: " + QUEUE_NAME);
        } catch (Exception e) {
            System.out.println("⚠ RabbitMQ not available, continuing with file export only.");
        }

        // Export alerts to JSON files and optionally to RabbitMQ
        int count = 0;
        for (JSONObject alert : alerts) {
            String eventId = alert.getString("event_id");
            File outFile = new File(outputDir, eventId + ".json");

            try (FileWriter writer = new FileWriter(outFile)) {
                writer.write(alert.toString(4)); // pretty-print
            } catch (Exception e) {
                System.out.println("Failed to write " + outFile.getName());
                e.printStackTrace();
            }

            if (channel != null) {
                try {
                    channel.basicPublish("", QUEUE_NAME, null, alert.toString().getBytes("UTF-8"));
                } catch (Exception e) {
                    System.out.println("Failed to send " + eventId + " to RabbitMQ.");
                    e.printStackTrace();
                }
            }

            count++;
        }

        // Cleanup RabbitMQ
        if (channel != null) channel.close();
        if (rabbitConn != null) rabbitConn.close();

        System.out.println("✅ Exported " + count + " alerts with severity = " + severity + " to output/");
    }
}
