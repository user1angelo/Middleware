

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Standalone test utility that sends a simulated ransomware mitigation command
 * to the OpenDaylightModule via RabbitMQ.
 *
 * Usage:
 *   java -cp .:amqp-client-5.16.0.jar:json-20231013.jar com.nis1.thesis.test.SendRansomwareAlert
 */
public class SendRansomwareAlert {

    private static final String RABBITMQ_HOST = "localhost";
    private static final int RABBITMQ_PORT = 5672;
    private static final String RABBITMQ_USER = "user";
    private static final String RABBITMQ_PASSWORD = "password";

    private static final String COMMAND_QUEUE = "odl_commands_queue";
    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public static void main(String[] args) {
        System.out.println("🚨 Sending simulated ransomware alert to OpenDaylightModule...");

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            channel.queueDeclare(COMMAND_QUEUE, true, false, false, null);

            // Build the simulated ransomware alert → quarantine command
            JSONObject message = new JSONObject();
            message.put("message_type", "workflow_command");
            message.put("event_id", "sim-ransomware-" + UUID.randomUUID());
            message.put("timestamp", getCurrentManilaTime());
            message.put("event_type", "security.alert");
            message.put("source_module", "RansomwareSimulator");

            JSONObject payload = new JSONObject();
            payload.put("command", "INITIATE_MITIGATION");

            JSONObject parameters = new JSONObject();
            parameters.put("targetHost", "10.0.0.1/32");
            parameters.put("action", "QUARANTINE");
            parameters.put("family", "RANSOMWARE_TEST");
            parameters.put("description", "Simulated ransomware detection on 10.0.0.1");
            payload.put("parameters", parameters);

            message.put("payload", payload);

            // Publish to the OpenDaylight command queue
            channel.basicPublish("", COMMAND_QUEUE, null,
                    message.toString().getBytes(StandardCharsets.UTF_8));

            System.out.println("✅ Simulated ransomware mitigation command sent:");
            System.out.println(message.toString(4));

        } catch (Exception e) {
            System.err.println("❌ Failed to send test message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String getCurrentManilaTime() {
        ZonedDateTime manilaTime = ZonedDateTime.now(MANILA_ZONE);
        return manilaTime.format(ISO_FORMATTER);
    }
}
