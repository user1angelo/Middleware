package com.yourorg.registry;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.PluggableModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.google.gson.Gson;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * SdkModuleHost
 *
 * Lightweight host that initializes SDK-based pluggable modules which are
 * already present on the JVM classpath (e.g. user-defined-modules/*.jar).
 *
 * It provides a real CoreSystemApi implementation that bridges SDK events
 * to the RabbitMQ-based ModuleRegistry messaging pipeline.
 */
public class SdkModuleHost {

    private final Map<String, PluggableModule> activeModules = new HashMap<>();
    private Connection rabbitConnection;
    private Channel rabbitChannel;
    private final Gson gson = new Gson();

    /**
     * CoreSystemApi implementation that publishes to RabbitMQ
     */
    private class RabbitMqCoreSystemApi implements CoreSystemApi {
        private final Map<String, Consumer<Event<?>>> listeners = new HashMap<>();

        @Override
        public void publishEvent(Event<?> event) {
            String workflowQueue = ConfigLoader.getWorkflowQueueName();

            try {
                if (rabbitChannel == null || !rabbitChannel.isOpen()) {
                    System.err.println("[SdkModuleHost] RabbitMQ channel not open, dropping event: " + event.getId());
                    return;
                }

                // Convert SDK Event to standard system message envelope
                JSONObject message = new JSONObject();
                message.put("message_type", "alert"); // Treat all published events as alerts for now
                message.put("event_id", event.getId());
                message.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(event.getTimestamp()));
                message.put("event_type", event.getType());
                message.put("source_module", "SdkModuleHost");

                // Serialize payload data using Gson
                String dataJson = gson.toJson(event.getData());
                message.put("payload", new JSONObject(dataJson));

                byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
                rabbitChannel.basicPublish("", workflowQueue, null, body);

                System.out.println("[SdkModuleHost] Published event to " + workflowQueue + " type=" + event.getType());

            } catch (IOException e) {
                System.err.println("[SdkModuleHost] Failed to publish event: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void subscribeToEvent(String eventType, Consumer<Event<?>> listener) {
            System.out.println("[SdkModuleHost][CoreSystemApi] subscribeToEvent pattern=" + eventType);
            listeners.put(eventType, listener);
            // TODO: Implement RabbitMQ consumption for subscriptions if needed
        }
    }

    /**
     * Initialize all SDK-based modules that we know about on the current
     * classpath.
     */
    public void initializeModules() {
        // 1. Setup RabbitMQ connection
        setupRabbitMQ();

        // 2. Create API instance
        CoreSystemApi api = new RabbitMqCoreSystemApi();

        // 3. Initialize known modules
        // OpenDaylight
        initializeSingleModule("com.nis1.thesis.udm.OpenDaylightModule", api);

        // Suricata HTTP Module
        initializeSingleModule("com.nis1.thesis.udm.SuricataHttpModule", api);

        System.out.println("[SdkModuleHost] Active SDK modules: " + activeModules.keySet());
    }

    private void setupRabbitMQ() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(ConfigLoader.getRabbitMQHost());
        factory.setPort(ConfigLoader.getRabbitMQPort());
        factory.setUsername(ConfigLoader.getRabbitMQUser());
        factory.setPassword(ConfigLoader.getRabbitMQPassword());

        try {
            rabbitConnection = factory.newConnection();
            rabbitChannel = rabbitConnection.createChannel();

            String workflowQueue = ConfigLoader.getWorkflowQueueName();
            rabbitChannel.queueDeclare(workflowQueue, true, false, false, null);

            System.out.println("[SdkModuleHost] Connected to RabbitMQ at " + ConfigLoader.getRabbitMQHost());

        } catch (IOException | TimeoutException e) {
            System.err.println("[SdkModuleHost] Failed to connect to RabbitMQ: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initializeSingleModule(String className, CoreSystemApi api) {
        try {
            Class<?> clazz = Class.forName(className);
            if (!PluggableModule.class.isAssignableFrom(clazz)) {
                System.out.println(
                        "[SdkModuleHost] Class " + className + " does not implement PluggableModule; skipping.");
                return;
            }

            @SuppressWarnings("unchecked")
            PluggableModule module = (PluggableModule) clazz.getDeclaredConstructor().newInstance();

            System.out.println("[SdkModuleHost] Initializing SDK module: " + className);
            module.initialize(api);

            activeModules.put(className, module);
            System.out.println("[SdkModuleHost] Initialized module: " + module.getName());

        } catch (ClassNotFoundException e) {
            System.out.println("[SdkModuleHost] SDK module class not found on classpath: " + className);
        } catch (Throwable t) {
            System.err.println("[SdkModuleHost] Failed to initialize SDK module " + className + ": " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Gracefully shut down all SDK-based modules that were initialized.
     */
    public void shutdownModules() {
        for (Map.Entry<String, PluggableModule> entry : activeModules.entrySet()) {
            try {
                System.out.println("[SdkModuleHost] Shutting down module: " + entry.getKey());
                entry.getValue().shutdown();
            } catch (Throwable t) {
                System.err
                        .println("[SdkModuleHost] Error during shutdown of " + entry.getKey() + ": " + t.getMessage());
            }
        }
        activeModules.clear();

        try {
            if (rabbitChannel != null && rabbitChannel.isOpen())
                rabbitChannel.close();
            if (rabbitConnection != null && rabbitConnection.isOpen())
                rabbitConnection.close();
        } catch (Exception e) {
            System.err.println("[SdkModuleHost] Error closing RabbitMQ: " + e.getMessage());
        }
    }
}
