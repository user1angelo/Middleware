package com.nis1.thesis.udm;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.google.gson.Gson;
import com.nis1.thesis.sdk.AlertEnvelopeBuilder;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail2banModule - User-Defined Module for Fail2ban Brute-Force Detection Integration
 *
 * Standalone Java process that:
 * - Registers itself with the ModuleRegistry via RabbitMQ
 * - Sends periodic heartbeats to maintain online status
 * - Tails Fail2ban's log file in real-time (same WatchService/RandomAccessFile pattern
 *   SuricataModule uses for eve.json, since Fail2ban is another real tool that writes a
 *   growing local log file)
 * - Transforms Fail2ban ban events into standardized alerts.host.fail2ban events
 * - Listens for orchestration commands from the workflow engine
 *
 * Unlike Suricata/Maltrail, Fail2ban's log format is plain structured text, not JSON - this
 * module parses it with a regex instead of Gson, demonstrating the SDK handles a third,
 * genuinely different message shape with zero changes to CoreSystemApi, WorkflowMatcher,
 * WorkflowEngine, or OpenDaylightModule.
 */
public class Fail2banModule {

    // --- Configuration File -------------------------------------------------

    private static final String CONFIG_PATH = "config/fail2ban-module.properties";

    // --- Module Identity (loaded from config) -------------------------------

    private static String MODULE_ID = "fail2ban-module";
    private static String MODULE_NAME = "Fail2ban UDM";
    private static String MODULE_TYPE = "host_security";
    private static String COMMAND_QUEUE = "fail2ban-module_commands_queue";
    private static String MODULE_CAPABILITIES = "brute_force_detection,alert_generation";

    // --- RabbitMQ Configuration (align with ModuleRegistry) -----------------

    private static String RABBITMQ_HOST = "localhost";
    private static int RABBITMQ_PORT = 5672;
    private static String RABBITMQ_USER = "user";
    private static String RABBITMQ_PASSWORD = "password";
    private static String WORKFLOW_QUEUE = "workflow_queue"; // UDM -> ModuleRegistry

    // --- Fail2ban Configuration ----------------------------------------------

    // Repo-relative default (not /var/log/...) so the demo works out of the box without a
    // real fail2ban install - the module doesn't know or care whether a real fail2ban wrote
    // the line or a simulator script appended it. Real deployments should point this at
    // /var/log/fail2ban/fail2ban.log via the properties file.
    private static String FAIL2BAN_LOG_PATH = "simulated_logs/fail2ban.log";

    // --- Heartbeat Configuration --------------------------------------------

    private static int HEARTBEAT_INTERVAL = 30; // seconds

    // --- Time ---------------------------------------------------------------

    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Gson gson = new Gson();

    // Matches fail2ban's default log line, e.g.:
    // 2026-07-25 10:23:45,678 fail2ban.actions        [12345]: NOTICE  [sshd] Ban 192.168.1.50
    private static final Pattern FAIL2BAN_LINE_PATTERN = Pattern.compile(
            "^\\S+\\s+\\S+\\s+fail2ban\\.actions\\s+\\[\\d+\\]:\\s+(\\w+)\\s+\\[([\\w.-]+)\\]\\s+(Ban|Unban)\\s+(\\S+)\\s*$");

    // --- Runtime state ------------------------------------------------------

    private static ScheduledExecutorService scheduler;
    private static ExecutorService fileWatcher;
    private static volatile boolean running = true;
    private static long moduleStartTime = System.currentTimeMillis();

    public static void main(String[] args) {
        printBanner();

        // Load configuration from properties file
        loadConfig();

        try {
            // Ensure the log file exists (simulating an "already configured" Fail2ban
            // install) rather than blocking indefinitely like SuricataModule does for a
            // real external tool it must wait to start.
            Path logPath = Paths.get(FAIL2BAN_LOG_PATH);
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            if (!Files.exists(logPath)) {
                Files.createFile(logPath);
                System.out.println("📝 Created fail2ban log file at: " + FAIL2BAN_LOG_PATH);
            }

            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(RABBITMQ_HOST);
            factory.setPort(RABBITMQ_PORT);
            factory.setUsername(RABBITMQ_USER);
            factory.setPassword(RABBITMQ_PASSWORD);

            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();

            // Declare queues
            channel.queueDeclare(WORKFLOW_QUEUE, true, false, false, null);
            channel.queueDeclare(COMMAND_QUEUE, true, false, false, null);

            System.out.println("✅ Connected to RabbitMQ at " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);

            // 1) Register module with ModuleRegistry
            sendRegistration(channel);
            Thread.sleep(1000);

            // 2) Start heartbeat sender
            startHeartbeats(channel);

            // 3) Start command listener
            startCommandListener(channel);

            // 4) Start fail2ban.log monitoring
            startFail2banLogMonitoring(channel);

            System.out.println("\n✅ Fail2banModule is running. Press Ctrl+C to stop.\n");

            // Graceful shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Shutting down Fail2banModule...");
                running = false;
                if (scheduler != null)
                    scheduler.shutdownNow();
                if (fileWatcher != null)
                    fileWatcher.shutdownNow();
            }));

            // Keep main thread alive
            while (running) {
                Thread.sleep(1000);
            }

            // Cleanup
            channel.close();
            connection.close();

        } catch (Exception e) {
            System.err.println("❌ Fail2banModule fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (scheduler != null)
                scheduler.shutdownNow();
            if (fileWatcher != null)
                fileWatcher.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------
    // Registration & Heartbeat
    // ---------------------------------------------------------------------

    private static void sendRegistration(Channel channel) throws IOException {
        JSONObject registration = new JSONObject();
        registration.put("message_type", "registration");
        registration.put("event_id", "reg-" + UUID.randomUUID().toString());
        registration.put("timestamp", getCurrentManilaTime());
        registration.put("event_type", "system.module.registration");
        registration.put("source_module", MODULE_NAME);

        JSONObject payload = new JSONObject();
        payload.put("module_id", MODULE_ID);
        payload.put("module_name", MODULE_NAME);
        payload.put("module_type", MODULE_TYPE);

        JSONArray capabilities = new JSONArray();
        for (String cap : MODULE_CAPABILITIES.split(",")) {
            capabilities.put(cap.trim());
        }
        payload.put("capabilities", capabilities);

        payload.put("command_queue", COMMAND_QUEUE);
        payload.put("version", "1.0.0");

        JSONObject metadata = new JSONObject();
        metadata.put("vendor", "Fail2ban");
        metadata.put("log_source", FAIL2BAN_LOG_PATH);
        metadata.put("detection_type", "host_brute_force");
        payload.put("metadata", metadata);

        registration.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                registration.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("📝 Sent Fail2banModule registration to ModuleRegistry");
    }

    private static void startHeartbeats(Channel channel) {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeat(channel);
            } catch (Exception e) {
                System.err.println("❌ Heartbeat failed: " + e.getMessage());
            }
        }, 5, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);

        System.out.println("💓 Heartbeat sender started (every " + HEARTBEAT_INTERVAL + " seconds)");
    }

    private static void sendHeartbeat(Channel channel) throws IOException {
        JSONObject heartbeat = new JSONObject();
        heartbeat.put("message_type", "heartbeat");
        heartbeat.put("event_id", "hb-" + UUID.randomUUID().toString());
        heartbeat.put("timestamp", getCurrentManilaTime());
        heartbeat.put("event_type", "system.module.heartbeat");
        heartbeat.put("source_module", MODULE_NAME);

        JSONObject payload = new JSONObject();
        payload.put("module_id", MODULE_ID);
        payload.put("status", "online");
        payload.put("uptime_seconds", (System.currentTimeMillis() - moduleStartTime) / 1000);

        heartbeat.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                heartbeat.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("💓 Sent heartbeat");
    }

    // ---------------------------------------------------------------------
    // Command Listener
    // ---------------------------------------------------------------------

    private static void startCommandListener(Channel channel) {
        new Thread(() -> {
            try {
                System.out.println("🎯 Command listener started on: " + COMMAND_QUEUE);

                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    try {
                        JSONObject json = new JSONObject(message);

                        System.out.println("\n🎯 Received command:");
                        System.out.println(json.toString(2));

                        String eventType = json.optString("event_type", "");
                        handleCommand(eventType, json, channel);

                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    } catch (Exception e) {
                        System.err.println("❌ Error processing command: " + e.getMessage());
                        channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                    }
                };

                channel.basicConsume(COMMAND_QUEUE, false, deliverCallback, consumerTag -> {
                });

            } catch (Exception e) {
                System.err.println("❌ Command listener error: " + e.getMessage());
            }
        }).start();
    }

    private static void handleCommand(String eventType, JSONObject command, Channel channel) {
        switch (eventType) {
            case "command.fail2ban.status":
                System.out.println("📋 Processing status command");
                break;
            default:
                System.out.println("⚠️  Unknown command type: " + eventType);
        }
    }

    // ---------------------------------------------------------------------
    // Fail2ban Log Monitoring
    // ---------------------------------------------------------------------

    /**
     * Starts real-time monitoring of Fail2ban's log file, same WatchService/RandomAccessFile
     * tailing pattern SuricataModule uses for eve.json.
     */
    private static void startFail2banLogMonitoring(Channel channel) {
        fileWatcher = Executors.newSingleThreadExecutor();

        fileWatcher.submit(() -> {
            System.out.println("📡 Starting real-time fail2ban.log monitoring...");
            System.out.println("   Monitoring: " + FAIL2BAN_LOG_PATH + "\n");

            try {
                Path logPath = Paths.get(FAIL2BAN_LOG_PATH);
                Path dir = logPath.getParent();

                try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                    dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

                    long lastPosition = Files.size(logPath);
                    System.out.println("✅ Monitoring started from position: " + lastPosition);

                    while (running && !Thread.currentThread().isInterrupted()) {
                        WatchKey key = watchService.poll(1, TimeUnit.SECONDS);

                        if (key == null) {
                            continue;
                        }

                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path changed = (Path) event.context();

                            if (changed.endsWith(logPath.getFileName())) {
                                try (RandomAccessFile raf = new RandomAccessFile(logPath.toFile(), "r")) {
                                    long currentSize = raf.length();

                                    if (currentSize > lastPosition) {
                                        raf.seek(lastPosition);

                                        String line;
                                        while ((line = raf.readLine()) != null) {
                                            if (!line.trim().isEmpty()) {
                                                try {
                                                    parseFail2banLine(line, channel);
                                                } catch (Exception e) {
                                                    System.err.println("❌ Error parsing line: " + e.getMessage());
                                                }
                                            }
                                        }

                                        lastPosition = raf.getFilePointer();
                                    } else if (currentSize < lastPosition) {
                                        System.out.println("⚠️  fail2ban.log file rotated. Starting from beginning.");
                                        lastPosition = 0;
                                    }
                                }
                            }
                        }

                        key.reset();
                    }

                } catch (InterruptedException e) {
                    System.out.println("ℹ️  fail2ban.log monitoring interrupted");
                    Thread.currentThread().interrupt();
                }

            } catch (Exception e) {
                System.err.println("❌ Failed to monitor fail2ban.log file: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Parses a single line from fail2ban's log file and publishes a standardized alert if
     * it's a Ban/Unban actions line. Package-private (not private) so it can be unit tested
     * directly without reflection.
     */
    static void parseFail2banLine(String line, Channel channel) {
        try {
            if (line == null || !line.contains("fail2ban.actions")) {
                // Not an actions line (e.g. a "Found" pre-ban attempt log) - silently ignored,
                // mirrors SuricataModule's silent skip of non-alert eve.json event types.
                return;
            }

            Matcher matcher = FAIL2BAN_LINE_PATTERN.matcher(line.trim());
            if (!matcher.matches()) {
                System.err.println("⚠️  Fail2ban actions line missing required fields (jail/action/ip) - line: "
                        + line);
                return;
            }

            String jail = matcher.group(2);
            String action = matcher.group(3);
            String ip = matcher.group(4);

            if (!"Ban".equalsIgnoreCase(action)) {
                // Unban events are benign (expiry/manual unban) - not alert-worthy.
                return;
            }

            publishFail2banAlert(jail, ip, channel);

        } catch (Exception e) {
            System.err.println("❌ Failed to parse fail2ban line: " + e.getMessage());
        }
    }

    /**
     * Publishes standardized alerts.host.fail2ban event
     */
    private static void publishFail2banAlert(String jail, String ip, Channel channel) throws IOException {
        JSONObject alert = buildFail2banAlertJson(jail, ip);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                alert.toString().getBytes(StandardCharsets.UTF_8));

        JSONObject payload = alert.getJSONObject("payload");
        String logMessage = String.format(
                "📤 Published Fail2ban alert: %s [Severity: %s] source_ip=%s jail=%s",
                payload.optString("signature"), payload.optString("severity"), ip, jail);
        System.out.println(logMessage);
    }

    /**
     * Builds the standardized alerts.host.fail2ban JSON envelope (including payload) without
     * publishing it. Package-private (not private) so it can be unit tested directly -
     * asserting on severity/category/threat scoring - without needing a live RabbitMQ broker.
     */
    static JSONObject buildFail2banAlertJson(String jail, String ip) {
        String timestamp = Instant.now().toString();

        JSONObject telemetry = new JSONObject();
        telemetry.put("alert_generated_time", timestamp);
        telemetry.put("alert_generated_time_ms", System.currentTimeMillis());
        telemetry.put("system_received_time", timestamp);
        telemetry.put("system_received_time_ms", System.currentTimeMillis());
        telemetry.put("alert_to_received_delay_sec", 0.0);

        Fail2banAlertData payloadData = new Fail2banAlertData();
        payloadData.setAlertId(generateAlertId());
        payloadData.setSignature("SSH brute-force (jail: " + jail + ")");
        payloadData.setSourceIp(ip);
        payloadData.setSeverity("high"); // a completed ban is fail2ban's own decision to block - a strong signal
        payloadData.setCategory("brute_force");
        payloadData.setAlertType("brute_force");
        payloadData.setThreatScore(calculateThreatScore(jail));
        payloadData.setConfidenceScore(92);
        payloadData.setJail(jail);
        payloadData.setBanAction("Ban");

        String payloadJson = gson.toJson(payloadData);

        return AlertEnvelopeBuilder.create()
                .timestamp(Instant.parse(timestamp))
                .telemetry(telemetry)
                .eventType("alerts.host.fail2ban")
                .sourceModule(MODULE_NAME)
                .payload(new JSONObject(payloadJson))
                .build();
    }

    // ---------------------------------------------------------------------
    // Utility Methods
    // ---------------------------------------------------------------------

    private static String generateAlertId() {
        return "F2B-" + System.currentTimeMillis() + "-" +
                String.format("%04d", new Random().nextInt(10000));
    }

    private static int calculateThreatScore(String jail) {
        int baseScore = 65;
        if (jail != null && jail.toLowerCase().contains("ssh")) {
            baseScore += 10; // SSH brute-force is particularly notable
        }
        return Math.min(100, baseScore);
    }

    private static String getCurrentManilaTime() {
        ZonedDateTime manilaTime = ZonedDateTime.now(MANILA_ZONE);
        return manilaTime.format(ISO_FORMATTER);
    }

    private static void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);

            RABBITMQ_HOST = props.getProperty("rabbitmq.host", RABBITMQ_HOST);
            RABBITMQ_PORT = Integer.parseInt(props.getProperty("rabbitmq.port", String.valueOf(RABBITMQ_PORT)));
            RABBITMQ_USER = props.getProperty("rabbitmq.user", RABBITMQ_USER);
            RABBITMQ_PASSWORD = props.getProperty("rabbitmq.password", RABBITMQ_PASSWORD);
            WORKFLOW_QUEUE = props.getProperty("rabbitmq.workflow_queue", WORKFLOW_QUEUE);

            FAIL2BAN_LOG_PATH = props.getProperty("fail2ban.log_path", FAIL2BAN_LOG_PATH);

            MODULE_ID = props.getProperty("module.id", MODULE_ID);
            MODULE_NAME = props.getProperty("module.name", MODULE_NAME);
            MODULE_TYPE = props.getProperty("module.type", MODULE_TYPE);
            COMMAND_QUEUE = props.getProperty("module.command_queue", COMMAND_QUEUE);
            MODULE_CAPABILITIES = props.getProperty("module.capabilities", MODULE_CAPABILITIES);

            HEARTBEAT_INTERVAL = Integer.parseInt(props.getProperty("module.heartbeat_interval",
                    String.valueOf(HEARTBEAT_INTERVAL)));

            System.out.println("🔧 Loaded Fail2banModule config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("⚠️  Could not load Fail2banModule config (using defaults): " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║  Fail2ban User-Defined Module (UDM)    ║");
        System.out.println("║  Brute-Force Detection Integration v1.0║");
        System.out.println("╚════════════════════════════════════════╝\n");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Module Type: " + MODULE_TYPE);
        System.out.println("Command Queue: " + COMMAND_QUEUE + "\n");
    }
}
