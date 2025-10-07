import com.rabbitmq.client.*;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.Scanner;
import java.util.UUID;

/**
 * Interactive testing tool for ThreatContextStore
 * Tests alert sending and query capabilities via RabbitMQ
 */
public class TCSTester {
    
    private static final String RABBITMQ_HOST = "192.168.86.76";
    private static final int RABBITMQ_PORT = 5672;
    private static final String RABBITMQ_USER = "guest";
    private static final String RABBITMQ_PASSWORD = "guest";
    private static final String ALERTS_QUEUE = "alerts_queue";
    private static final String WORKFLOW_QUEUE = "workflow_queue";
    
    private static final Random random = new Random();
    private static final Scanner scanner = new Scanner(System.in);
    
    // Manila timezone (GMT+8)
    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    
    // Random data pools - RANSOMWARE ONLY
    private static final String[] SEVERITIES = {"high", "critical"}; // Only high-severity ransomware
    private static final String[] ALERT_TYPES = {
        "ransomware_detection", "ransomware_encryption", "ransomware_propagation"
    };
    private static final String[] SIGNATURES = {
        "Suspicious file encryption activity", "Mass file modification detected",
        "Ransomware encryption pattern", "File system lockdown attempt",
        "Crypto-locker behavior detected", "Ransomware process execution",
        "Volume shadow copy deletion", "Backup deletion attempt",
        "Extension modification (.encrypted)", "Shell command execution"
    };
    private static final String[] PROTOCOLS = {"TCP", "UDP", "ICMP", "HTTP", "HTTPS"};
    
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║  ThreatContextStore Tester (TCSTester) ║");
        System.out.println("╚════════════════════════════════════════╝\n");
        
        System.out.println("What would you like to do?");
        System.out.println("1. Send random alerts");
        System.out.println("2. Query alerts");
        System.out.print("\nChoose option (1 or 2): ");
        
        String choice = scanner.nextLine().trim();
        
        try {
            if (choice.equals("1")) {
                handleSendAlerts();
            } else if (choice.equals("2")) {
                handleQueryAlerts();
            } else {
                System.out.println("❌ Invalid option. Exiting.");
            }
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle sending random alerts
     */
    private static void handleSendAlerts() throws Exception {
        System.out.println("\n=== Send Random Alerts ===");
        System.out.println("1. Send specific count");
        System.out.println("2. Send fixed number (10)");
        System.out.println("3. Keep sending until interrupted");
        System.out.print("Choose option: ");
        
        String mode = scanner.nextLine().trim();
        int count = 0;
        boolean infinite = false;
        
        switch (mode) {
            case "1":
                System.out.print("How many alerts to send? ");
                count = Integer.parseInt(scanner.nextLine().trim());
                break;
            case "2":
                count = 10;
                break;
            case "3":
                infinite = true;
                System.out.println("\n⚠️  Press any key and Enter to stop sending...");
                break;
            default:
                System.out.println("❌ Invalid option. Exiting.");
                return;
        }
        
        sendRandomAlerts(count, infinite);
    }
    
    /**
     * Send random alerts to RabbitMQ
     */
    private static void sendRandomAlerts(int count, boolean infinite) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);
        
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            
            // Declare both queues
            channel.queueDeclare(ALERTS_QUEUE, true, false, false, null);
            channel.queueDeclare(WORKFLOW_QUEUE, true, false, false, null);
            System.out.println("\n✅ Connected to RabbitMQ");
            System.out.println("✅ Alerts will be sent to BOTH queues (alerts_queue + workflow_queue)");
            
            if (infinite) {
                // Setup non-blocking input reader
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                int sent = 0;
                
                while (!reader.ready()) {
                    JSONObject alert = generateRandomAlert();
                    
                    // Send to both queues (broadcast pattern)
                    channel.basicPublish("", ALERTS_QUEUE, null, alert.toString().getBytes("UTF-8"));
                    channel.basicPublish("", WORKFLOW_QUEUE, null, alert.toString().getBytes("UTF-8"));
                    sent++;
                    
                    String severity = alert.getJSONObject("payload").getString("severity");
                    String alertType = alert.getJSONObject("payload").getString("alert_type");
                    System.out.println("📤 Sent alert #" + sent + " | Severity: " + severity + " | Type: " + alertType + " | To: both queues");
                    
                    Thread.sleep(500); // 0.5 second delay
                }
                
                System.out.println("\n✅ Stopped. Total sent: " + sent);
                
            } else {
                // Send fixed count
                System.out.println("📤 Sending " + count + " alerts...\n");
                
                for (int i = 1; i <= count; i++) {
                    JSONObject alert = generateRandomAlert();
                    
                    // Send to both queues (broadcast pattern)
                    channel.basicPublish("", ALERTS_QUEUE, null, alert.toString().getBytes("UTF-8"));
                    channel.basicPublish("", WORKFLOW_QUEUE, null, alert.toString().getBytes("UTF-8"));
                    
                    String severity = alert.getJSONObject("payload").getString("severity");
                    String alertType = alert.getJSONObject("payload").getString("alert_type");
                    System.out.println("📤 Sent alert " + i + "/" + count + " | Severity: " + severity + " | Type: " + alertType + " | To: both queues");
                    
                    Thread.sleep(500); // 0.5 second delay
                }
                
                System.out.println("\n✅ Successfully sent " + count + " alerts!");
            }
        }
    }
    
    /**
     * Generate a random alert message
     */
    private static JSONObject generateRandomAlert() {
        JSONObject alert = new JSONObject();
        alert.put("message_type", "alert");
        alert.put("event_id", UUID.randomUUID().toString());
        alert.put("timestamp", getCurrentManilaTime());
        alert.put("event_type", "alerts.host.wazuh");
        alert.put("source_module", "TCSTester");
        
        JSONObject payload = new JSONObject();
        
        // Random severity and alert type
        String severity = SEVERITIES[random.nextInt(SEVERITIES.length)];
        String alertType = ALERT_TYPES[random.nextInt(ALERT_TYPES.length)];
        String signature = SIGNATURES[random.nextInt(SIGNATURES.length)];
        
        payload.put("severity", severity);
        payload.put("alert_type", alertType);
        payload.put("signature_id", String.valueOf(9200000 + random.nextInt(10000)));
        payload.put("signature", signature);
        
        // Random host and network info
        payload.put("host_id", "host-192.168." + random.nextInt(256) + "." + random.nextInt(256));
        payload.put("source_ip", "192.168." + random.nextInt(256) + "." + random.nextInt(256));
        payload.put("destination_ip", "10.0." + random.nextInt(256) + "." + random.nextInt(256));
        payload.put("protocol", PROTOCOLS[random.nextInt(PROTOCOLS.length)]);
        
        // Random file/process paths
        boolean isWindows = random.nextBoolean();
        if (isWindows) {
            payload.put("process", "C:\\Users\\User" + random.nextInt(10) + "\\AppData\\Local\\Temp\\process" + random.nextInt(1000) + ".exe");
            payload.put("file_path", "C:\\Users\\User" + random.nextInt(10) + "\\Documents\\file" + random.nextInt(1000) + ".dat");
        } else {
            payload.put("process", "/tmp/process" + random.nextInt(1000));
            payload.put("file_path", "/home/user" + random.nextInt(10) + "/file" + random.nextInt(1000) + ".dat");
        }
        
        // High threat score for ransomware (70-100 range to match workflow conditions)
        payload.put("threat_score", 70 + random.nextInt(31)); // 70-100
        payload.put("matched_rule", "ransomware_rule_" + random.nextInt(100));
        
        alert.put("payload", payload);
        return alert;
    }
    
    /**
     * Handle querying alerts
     */
    private static void handleQueryAlerts() throws Exception {
        System.out.println("\n=== Query Alerts ===");
        
        JSONObject query = new JSONObject();
        query.put("message_type", "query");
        query.put("event_id", "query-" + UUID.randomUUID().toString());
        query.put("timestamp", getCurrentManilaTime());
        query.put("event_type", "query.request");
        query.put("source_module", "TCSTester");
        
        JSONObject payload = new JSONObject();
        JSONObject filters = new JSONObject();
        
        // Build filters interactively
        boolean addMore = true;
        int filterCount = 0;
        
        while (addMore) {
            System.out.print("\nEnter filter field (e.g., severity, alert_type): ");
            String field = scanner.nextLine().trim();
            
            if (field.isEmpty()) {
                System.out.println("⚠️  Field cannot be empty");
                continue;
            }
            
            System.out.print("Enter filter value: ");
            String value = scanner.nextLine().trim();
            
            if (value.isEmpty()) {
                System.out.println("⚠️  Value cannot be empty");
                continue;
            }
            
            filters.put(field, value);
            filterCount++;
            System.out.println("✅ Added filter: " + field + " = " + value);
            
            System.out.print("Add another filter? (y/n): ");
            String response = scanner.nextLine().trim().toLowerCase();
            addMore = response.equals("y") || response.equals("yes");
        }
        
        if (filterCount == 0) {
            System.out.println("❌ No filters added. Exiting.");
            return;
        }
        
        payload.put("filters", filters);
        
        // Optional: order by and limit
        System.out.print("\nOrder by field (default: timestamp): ");
        String orderBy = scanner.nextLine().trim();
        if (!orderBy.isEmpty()) {
            payload.put("order_by", orderBy);
        } else {
            payload.put("order_by", "timestamp");
        }
        
        System.out.print("Order direction (DESC/ASC, default: DESC): ");
        String orderDir = scanner.nextLine().trim().toUpperCase();
        if (!orderDir.isEmpty() && (orderDir.equals("DESC") || orderDir.equals("ASC"))) {
            payload.put("order_direction", orderDir);
        } else {
            payload.put("order_direction", "DESC");
        }
        
        System.out.print("Limit (default: 10): ");
        String limitStr = scanner.nextLine().trim();
        int limit = 10;
        if (!limitStr.isEmpty()) {
            try {
                limit = Integer.parseInt(limitStr);
            } catch (NumberFormatException e) {
                System.out.println("⚠️  Invalid number, using default: 10");
            }
        }
        payload.put("limit", limit);
        
        query.put("payload", payload);
        
        // Send query to RabbitMQ
        sendQuery(query);
    }
    
    /**
     * Send query to RabbitMQ
     */
    private static void sendQuery(JSONObject query) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setUsername(RABBITMQ_USER);
        factory.setPassword(RABBITMQ_PASSWORD);
        
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            
            channel.queueDeclare(ALERTS_QUEUE, true, false, false, null);
            channel.basicPublish("", ALERTS_QUEUE, null, query.toString().getBytes("UTF-8"));
            
            System.out.println("\n✅ Query sent successfully!");
            System.out.println("📋 Query ID: " + query.getString("event_id"));
            System.out.println("\n📊 Query Details:");
            System.out.println(query.toString(2));
            System.out.println("\n💡 ThreatContextStore will process this query and:");
            System.out.println("   - Send individual responses to 'query_response_queue' in RabbitMQ");
            System.out.println("   - Save response JSON files to 'ThreatContextStore/query_responses/' directory");
            System.out.println("   - Each response will be saved as {event_id}.json");
        }
    }
    
    /**
     * Get current time in Manila timezone (GMT+8)
     */
    private static String getCurrentManilaTime() {
        ZonedDateTime manilaTime = ZonedDateTime.now(MANILA_ZONE);
        return manilaTime.format(ISO_FORMATTER);
    }
}

