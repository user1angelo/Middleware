package com.nis1.thesis.eval;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.nis1.thesis.sdk.AlertEnvelopeBuilder;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * ============================================================================================
 *  SDK USABILITY EVALUATION - SKELETON 1 of 2 - STANDALONE PATTERN
 * ============================================================================================
 *
 * WHAT THIS IS: a fill-in-the-blank exercise. FooGuard is a fictional, lightweight host/network
 * intrusion detector. It writes one JSON line per detection to a local log file, e.g.:
 *
 *   {"ts":1753400000,"src":"10.0.0.5","dst":"203.0.113.9","sport":51234,"dport":22,
 *    "sev":"P2","rule":"ssh-brute-force","proto":"tcp"}
 *
 * ("P1".."P4", P1 = highest priority. FooGuard deliberately uses a different severity scale than
 * any real module in this repo, so mapping it is a genuine decision, not a copy-paste.)
 *
 * YOUR TASK: turn FooGuard's log lines into standardized alerts published to the SOAR bus, the
 * same way SuricataModule.java does for eve.json. There are 6 TODOs below. Everything else
 * (RabbitMQ plumbing, registration/heartbeat, the file-tail loop mechanics) is already written -
 * it's the same in every module in this repo and isn't really "the SDK," so it's given to keep
 * your attention on the parts that actually touch nis-thesis-sdk.
 *
 * BEFORE YOU START, read these two real, complete modules side by side with this skeleton:
 *   - user-defined-modules/src/main/java/com/nis1/thesis/udm/SuricataModule.java  (closest match -
 *     also file-tails a JSON-lines log)
 *   - user-defined-modules/src/main/java/com/nis1/thesis/udm/MaltrailModule.java  (a second
 *     worked example, different ingestion mechanism, same downstream shape)
 * Both are real, running modules in this codebase, not other teaching examples - what you see is
 * exactly the API surface a real developer worked with.
 *
 * WHEN YOU'RE DONE: `mvn -pl sdk-usability-evaluation compile` should succeed with no errors.
 * Each TODO below is tagged with the Clarke (2005) Cognitive Dimension it's meant to put you
 * face-to-face with - after finishing both skeletons, use those tags plus your own notes on where
 * you got stuck, had to guess, or found something surprisingly easy to rate the SDK against the
 * 12 dimensions defined in SDK_USABILITY_AUDIT.md (Part 3) at the repo root.
 * ============================================================================================
 */
public class FooGuardStandaloneSkeleton {

    // --- Configuration File -------------------------------------------------

    private static final String CONFIG_PATH = "config/foo-module.properties";

    // --- Module Identity (loaded from config) -------------------------------

    private static String MODULE_ID = "fooguard-module";
    private static String MODULE_NAME = "FooGuard UDM (Evaluation Skeleton)";
    private static String MODULE_TYPE = "network_security";
    private static String COMMAND_QUEUE = "fooguard-module_commands_queue";
    private static String MODULE_CAPABILITIES = "network_ids,alert_generation";

    // --- RabbitMQ Configuration ----------------------------------------------

    private static String RABBITMQ_HOST = "localhost";
    private static int RABBITMQ_PORT = 5672;
    private static String RABBITMQ_USER = "user";
    private static String RABBITMQ_PASSWORD = "password";
    private static String WORKFLOW_QUEUE = "workflow_queue";

    // --- FooGuard Configuration -----------------------------------------------

    // TODO-1 [Working Framework]: this is currently hardcoded. Real modules (see
    // Fail2banModule.FAIL2BAN_LOG_PATH / loadConfig()) read their tool-specific source path from
    // the .properties file, with this hardcoded value only as the fallback default. Fix
    // loadConfig() below (search for "TODO-1") to read a "foo.log_path" key from CONFIG_PATH.
    private static String FOOGUARD_LOG_PATH = "sample-data/foo.log";

    // --- Heartbeat Configuration --------------------------------------------

    private static int HEARTBEAT_INTERVAL = 30; // seconds

    // --- Time / JSON ---------------------------------------------------------

    private static final Gson gson = new Gson();

    // --- Runtime state ------------------------------------------------------

    private static ScheduledExecutorService scheduler;
    private static ExecutorService fileWatcher;
    private static volatile boolean running = true;
    private static long moduleStartTime = System.currentTimeMillis();

    public static void main(String[] args) {
        printBanner();
        loadConfig();

        try {
            Path logPath = Paths.get(FOOGUARD_LOG_PATH);
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            if (!Files.exists(logPath)) {
                Files.createFile(logPath);
                System.out.println("Created FooGuard log file at: " + FOOGUARD_LOG_PATH);
            }

            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(RABBITMQ_HOST);
            factory.setPort(RABBITMQ_PORT);
            factory.setUsername(RABBITMQ_USER);
            factory.setPassword(RABBITMQ_PASSWORD);

            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();

            channel.queueDeclare(WORKFLOW_QUEUE, true, false, false, null);
            channel.queueDeclare(COMMAND_QUEUE, true, false, false, null);

            System.out.println("Connected to RabbitMQ at " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);

            sendRegistration(channel);
            Thread.sleep(1000);
            startHeartbeats(channel);
            startCommandListener(channel);
            startFooGuardLogMonitoring(channel);

            System.out.println("\nFooGuardStandaloneSkeleton is running. Press Ctrl+C to stop.\n");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down FooGuardStandaloneSkeleton...");
                running = false;
                if (scheduler != null) scheduler.shutdownNow();
                if (fileWatcher != null) fileWatcher.shutdownNow();
            }));

            while (running) {
                Thread.sleep(1000);
            }

            channel.close();
            connection.close();

        } catch (Exception e) {
            System.err.println("FooGuardStandaloneSkeleton fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (scheduler != null) scheduler.shutdownNow();
            if (fileWatcher != null) fileWatcher.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------
    // Registration & Heartbeat (given - identical in every module, not SDK-specific)
    // ---------------------------------------------------------------------

    private static void sendRegistration(Channel channel) throws IOException {
        JSONObject registration = new JSONObject();
        registration.put("message_type", "registration");
        registration.put("event_id", "reg-" + UUID.randomUUID().toString());
        registration.put("timestamp", Instant.now().toString());
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
        metadata.put("vendor", "FooGuard");
        metadata.put("log_source", FOOGUARD_LOG_PATH);
        payload.put("metadata", metadata);

        registration.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                registration.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Sent FooGuardStandaloneSkeleton registration to ModuleRegistry");
    }

    private static void startHeartbeats(Channel channel) {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeat(channel);
            } catch (Exception e) {
                System.err.println("Heartbeat failed: " + e.getMessage());
            }
        }, 5, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }

    private static void sendHeartbeat(Channel channel) throws IOException {
        JSONObject heartbeat = new JSONObject();
        heartbeat.put("message_type", "heartbeat");
        heartbeat.put("event_id", "hb-" + UUID.randomUUID().toString());
        heartbeat.put("timestamp", Instant.now().toString());
        heartbeat.put("event_type", "system.module.heartbeat");
        heartbeat.put("source_module", MODULE_NAME);

        JSONObject payload = new JSONObject();
        payload.put("module_id", MODULE_ID);
        payload.put("status", "online");
        payload.put("uptime_seconds", (System.currentTimeMillis() - moduleStartTime) / 1000);
        heartbeat.put("payload", payload);

        channel.basicPublish("", WORKFLOW_QUEUE, null,
                heartbeat.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void startCommandListener(Channel channel) {
        new Thread(() -> {
            try {
                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                };
                channel.basicConsume(COMMAND_QUEUE, false, deliverCallback, consumerTag -> {
                });
            } catch (Exception e) {
                System.err.println("Command listener error: " + e.getMessage());
            }
        }).start();
    }

    // ---------------------------------------------------------------------
    // FooGuard Log Monitoring (given - same WatchService/RandomAccessFile tailing pattern
    // SuricataModule and Fail2banModule use; not SDK-specific, just Java NIO)
    // ---------------------------------------------------------------------

    private static void startFooGuardLogMonitoring(Channel channel) {
        fileWatcher = Executors.newSingleThreadExecutor();

        fileWatcher.submit(() -> {
            System.out.println("Starting real-time foo.log monitoring: " + FOOGUARD_LOG_PATH);

            try {
                Path logPath = Paths.get(FOOGUARD_LOG_PATH);
                Path dir = logPath.getParent();

                try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                    dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

                    long lastPosition = Files.size(logPath);

                    while (running && !Thread.currentThread().isInterrupted()) {
                        WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                        if (key == null) continue;

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
                                                    parseFooGuardLine(line, channel);
                                                } catch (Exception e) {
                                                    System.err.println("Error parsing line: " + e.getMessage());
                                                }
                                            }
                                        }
                                        lastPosition = raf.getFilePointer();
                                    } else if (currentSize < lastPosition) {
                                        lastPosition = 0;
                                    }
                                }
                            }
                        }
                        key.reset();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                System.err.println("Failed to monitor foo.log: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------
    // >>> YOUR WORK STARTS HERE <<<
    // ---------------------------------------------------------------------

    /**
     * TODO-6 [Progressive Evaluation / Work-Step Unit]: parse a single foo.log JSON line and, if
     * it's well-formed and has the fields you need, publish a standardized alert. Follow
     * MaltrailModule.parseMaltrailPacket(...) as your model: validate the line looks like JSON,
     * deserialize into FooGuardEvent (below), check required fields aren't null, then call
     * buildFooGuardAlertJson(...) and publish the result to WORKFLOW_QUEUE via
     * channel.basicPublish("", WORKFLOW_QUEUE, null, alert.toString().getBytes(...)).
     *
     * Left package-private (not private) on purpose, same reason MaltrailModule does it: so this
     * could be unit tested directly without a live RabbitMQ broker, if you wanted to add tests.
     */
    static void parseFooGuardLine(String line, Channel channel) {
        // TODO-6: implement me
        throw new UnsupportedOperationException("TODO-6: parseFooGuardLine not implemented");
    }

    /**
     * TODO-5 [Abstraction Level / API Elaboration]: this is the core exercise. Build the full
     * alert envelope for one FooGuardEvent:
     *   1. Populate a FooGuardAlertData (see FooGuardAlertData.java - already complete) from the
     *      FooGuardEvent's fields, using mapSeverity()/categorize()/calculateThreatScore() below.
     *   2. Serialize it to JSON (see MaltrailModule.buildMaltrailAlertJson for the gson.toJson
     *      pattern).
     *   3. Wrap it using AlertEnvelopeBuilder (com.nis1.thesis.sdk.AlertEnvelopeBuilder) -
     *      .eventType("alerts.network.fooguard").sourceModule(MODULE_NAME).payload(...).build().
     *      This is the one class in the SDK whose entire job is to save you from hand-assembling
     *      the message_type/event_id/timestamp/event_type/source_module/payload envelope by hand
     *      - notice how much (or how little) it actually saves you versus just building a
     *      JSONObject yourself.
     *
     * Does NOT publish - parseFooGuardLine() does that. Left package-private so it could be unit
     * tested directly (asserting on severity/category/threat-score mapping) without a broker.
     */
    static JSONObject buildFooGuardAlertJson(FooGuardEvent event) {
        // TODO-5: implement me
        throw new UnsupportedOperationException("TODO-5: buildFooGuardAlertJson not implemented");
    }

    /**
     * TODO-2 [Domain Correspondence / Role Expressiveness]: FooGuard reports priority as
     * "P1".."P4" (P1 = highest). Map it to this system's lowercase critical/high/medium/low
     * scale. Decide what an absent or unrecognized value should default to, and be explicit about
     * why (compare to how MaltrailModule.mapSeverity() and SuricataModule.mapSeverityLevel()
     * each made a different default choice, for different reasons - read both before deciding
     * yours).
     *
     * Normalize to exact lowercase strings - see the case-sensitivity note in
     * sdk-usability-evaluation's caller docs / SDK_USABILITY_AUDIT.md about WorkflowMatcher's
     * severity comparison, and match what every existing module already does.
     */
    private static String mapSeverity(String fooPriority) {
        // TODO-2: implement me
        throw new UnsupportedOperationException("TODO-2: mapSeverity not implemented");
    }

    /**
     * TODO-3 [Role Expressiveness]: turn FooGuard's "rule" slug (e.g. "ssh-brute-force",
     * "port-scan", "sql-injection-attempt") into a category/alert_type string. Follow
     * SuricataModule.categorizeFromSignature(...) or MaltrailModule.categorizeFromInfo(...) as a
     * model for keyword-based categorization, or take a simpler approach - your call, but be
     * ready to justify it when you rate this dimension afterward.
     */
    private static String categorize(String rule) {
        // TODO-3: implement me
        throw new UnsupportedOperationException("TODO-3: categorize not implemented");
    }

    /**
     * TODO-4 [Abstraction Level]: derive a 0-100 threat score from the normalized severity and
     * category. See SuricataModule.calculateThreatScore(...) / MaltrailModule's equivalent for
     * the existing baseline+category-bump pattern - reuse it, adapt it, or justify a different
     * approach.
     */
    private static int calculateThreatScore(String severity, String category) {
        // TODO-4: implement me
        throw new UnsupportedOperationException("TODO-4: calculateThreatScore not implemented");
    }

    // ---------------------------------------------------------------------
    // Utility Methods (given)
    // ---------------------------------------------------------------------

    private static String generateAlertId() {
        return "FOO-" + System.currentTimeMillis() + "-" +
                String.format("%04d", new Random().nextInt(10000));
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

            // TODO-1 [Working Framework]: read FOOGUARD_LOG_PATH from a "foo.log_path" key here,
            // the same way every other TODO-1-adjacent line in this method reads its own key -
            // one line, following the exact pattern already used three times above.

            MODULE_ID = props.getProperty("module.id", MODULE_ID);
            MODULE_NAME = props.getProperty("module.name", MODULE_NAME);
            MODULE_TYPE = props.getProperty("module.type", MODULE_TYPE);
            COMMAND_QUEUE = props.getProperty("module.command_queue", COMMAND_QUEUE);
            MODULE_CAPABILITIES = props.getProperty("module.capabilities", MODULE_CAPABILITIES);

            HEARTBEAT_INTERVAL = Integer.parseInt(props.getProperty("module.heartbeat_interval",
                    String.valueOf(HEARTBEAT_INTERVAL)));

            System.out.println("Loaded FooGuard config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("Could not load FooGuard config (using defaults): " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("========================================");
        System.out.println(" FooGuard Evaluation Skeleton (Pattern A - Standalone)");
        System.out.println("========================================");
        System.out.println("Module ID: " + MODULE_ID);
        System.out.println("Command Queue: " + COMMAND_QUEUE + "\n");
    }

    // ---------------------------------------------------------------------
    // Data structure for parsing FooGuard's JSON log lines (given)
    // ---------------------------------------------------------------------

    static class FooGuardEvent {
        @SerializedName("ts")
        Long timestamp; // unix seconds

        @SerializedName("src")
        String srcIp;

        @SerializedName("dst")
        String dstIp;

        @SerializedName("sport")
        Integer srcPort;

        @SerializedName("dport")
        Integer dstPort;

        @SerializedName("sev")
        String severity; // "P1".."P4"

        @SerializedName("rule")
        String rule;

        @SerializedName("proto")
        String proto;
    }
}
