
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
 * This utility allows testing of the automatic host isolation system by sending
 * a ransomware alert for a specified IP address.
 *
 * Usage:
 * Windows:
 * java -cp ".;lib/*" SendRansomwareAlert [IP_ADDRESS]
 * 
 * Linux/Mac:
 * java -cp ".:lib/*" SendRansomwareAlert [IP_ADDRESS]
 *
 * Examples:
 * java -cp ".;lib/*" SendRansomwareAlert 192.168.1.100
 * java -cp ".;lib/*" SendRansomwareAlert 10.0.0.50
 * java -cp ".;lib/*" SendRansomwareAlert (uses default: 10.0.0.1)
 */
public class SendRansomwareAlert {

    private static final String RABBITMQ_HOST = "localhost";
    private static final int RABBITMQ_PORT = 5672;
    private static final String RABBITMQ_USER = "user";
    private static final String RABBITMQ_PASSWORD = "password";

    private static final String COMMAND_QUEUE = "odl_commands_queue";
    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String DEFAULT_TARGET_IP = "10.0.0.1";

    public static void main(String[] args) {
        // Parse target IP from command-line argument or use default
        String targetIp = DEFAULT_TARGET_IP;

        if (args.length > 0) {
            String providedIp = args[0].trim();
            if (isValidIpAddress(providedIp)) {
                targetIp = providedIp;
            } else {
                System.err.println("❌ Invalid IP address format: " + providedIp);
                System.err.println("Usage: java -cp \".;lib/*\" SendRansomwareAlert [IP_ADDRESS]");
                System.err.println("Example: java -cp \".;lib/*\" SendRansomwareAlert 192.168.1.100");
                System.exit(1);
            }
        }

        System.out.println("🚨 Sending simulated ransomware alert to OpenDaylightModule...");
        System.out.println("🎯 Target IP for isolation: " + targetIp);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);

        try (Connection connection = factory.newConnection();
                Channel channel = connection.createChannel()) {

            channel.queueDeclare(COMMAND_QUEUE, true, false, false, null);

            // Build the standard INITIATE_MITIGATION event
            JSONObject message = new JSONObject();
            message.put("message_type", "workflow_command");
            message.put("event_id", "sim-ransomware-" + UUID.randomUUID());
            message.put("timestamp", getCurrentManilaTime());
            message.put("event_type", "INITIATE_MITIGATION"); // Must match what ODL subscribes to
            message.put("source_module", "RansomwareSimulator");

            JSONObject payload = new JSONObject();
            payload.put("targetHost", targetIp); // User-specified or default IP
            payload.put("action", "ISOLATE_VLAN"); // Enum matching MitigationAction
            payload.put("justification", "Simulated ransomware detection for IP: " + targetIp);
            payload.put("priority", "high");
            payload.put("sdn_controller", "opendaylight");

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

    /**
     * Validates IPv4 address format.
     * 
     * @param ip The IP address string to validate
     * @return true if valid IPv4 format, false otherwise
     */
    private static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        // Simple IPv4 validation regex
        String ipv4Pattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
        return ip.matches(ipv4Pattern);
    }

    private static String getCurrentManilaTime() {
        ZonedDateTime manilaTime = ZonedDateTime.now(MANILA_ZONE);
        return manilaTime.format(ISO_FORMATTER);
    }
}
