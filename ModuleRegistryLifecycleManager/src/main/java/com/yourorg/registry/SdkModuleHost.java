package com.yourorg.registry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.MitigationAction;
import com.nis1.thesis.sdk.MitigationCommandData;
import com.nis1.thesis.sdk.PluggableModule;

/**
 * SdkModuleHost
 *
 * Hosts SDK-based pluggable modules and bridges them to the ModuleRegistry
 * event loop.
 * 
 * Upgraded from stub to real event dispatcher.
 */
public class SdkModuleHost {

    private final Map<String, PluggableModule> activeModules = new HashMap<>();
    private final RealCoreSystemApi api = new RealCoreSystemApi();
    private ModuleRegistry registry; // Reference to registry for auto-registration

    private static class RealCoreSystemApi implements CoreSystemApi {
        // pattern -> list of listeners
        private final Map<String, List<Consumer<Event<?>>>> listeners = new ConcurrentHashMap<>();

        private com.rabbitmq.client.Channel channel;
        private String workflowQueue;

        public void initRabbitMq() {
            try {
                com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
                factory.setHost(ConfigLoader.getRabbitMQHost());
                factory.setPort(ConfigLoader.getRabbitMQPort());
                factory.setUsername(ConfigLoader.getRabbitMQUser());
                factory.setPassword(ConfigLoader.getRabbitMQPassword());

                com.rabbitmq.client.Connection connection = factory.newConnection();
                this.channel = connection.createChannel();
                this.workflowQueue = ConfigLoader.getWorkflowQueueName();
                this.channel.queueDeclare(workflowQueue, true, false, false, null);
                System.out.println("[SdkModuleHost] RabbitMQ connection initialized for publishing events.");
            } catch (Exception e) {
                System.err.println("[SdkModuleHost] Failed to initialize RabbitMQ connection: " + e.getMessage());
            }
        }

        @Override
        public void publishEvent(Event<?> event) {
            System.out.println("[SdkModuleHost] Module published event: " + event.getType());

            try {
                // Only forward alerts or predefined types
                if (event.getType().startsWith("alerts.")) {
                    JSONObject alert = new JSONObject();
                    alert.put("message_type", "alert");
                    alert.put("event_id", event.getId());
                    alert.put("timestamp", event.getTimestamp().toString());
                    alert.put("event_type", event.getType());
                    alert.put("source_module", "SdkModuleHost");

                    // Serialize payload
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    String payloadStr = gson.toJson(event.getData());
                    alert.put("payload", new JSONObject(payloadStr));

                    if (channel != null && workflowQueue != null) {
                        channel.basicPublish("", workflowQueue, null,
                                alert.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        System.out.println(
                                "[SdkModuleHost] Forwarded alert to RabbitMQ workflow_queue: " + event.getType());
                    } else {
                        System.err.println("[SdkModuleHost] RabbitMQ channel not initialized. Cannot forward alert.");
                    }
                }
            } catch (Exception e) {
                System.err.println("[SdkModuleHost] Error publishing event to RabbitMQ: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void subscribeToEvent(String eventType, Consumer<Event<?>> listener) {
            System.out.println("[SdkModuleHost] Module subscribed to: " + eventType);
            listeners.computeIfAbsent(eventType, k -> Collections.synchronizedList(new ArrayList<>())).add(listener);
        }

        // Get all subscribed event types (capabilities)
        public List<String> getCapabilities() {
            return new ArrayList<>(listeners.keySet());
        }

        public void dispatchLocal(Event<?> event) {
            // 1. Exact match
            if (listeners.containsKey(event.getType())) {
                for (Consumer<Event<?>> listener : listeners.get(event.getType())) {
                    try {
                        listener.accept(event);
                    } catch (Exception e) {
                        System.err.println(
                                "[SdkModuleHost] Error in listener for " + event.getType() + ": " + e.getMessage());
                    }
                }
            }

            // 2. Wildcard match (e.g. "odl.*" matches "odl.host.isolate")
            for (Map.Entry<String, List<Consumer<Event<?>>>> entry : listeners.entrySet()) {
                String pattern = entry.getKey();
                if (pattern.endsWith(".*")) {
                    String prefix = pattern.substring(0, pattern.length() - 1); // "odl."
                    if (event.getType().startsWith(prefix) && !pattern.equals(event.getType())) {
                        for (Consumer<Event<?>> listener : entry.getValue()) {
                            try {
                                listener.accept(event);
                            } catch (Exception e) {
                                System.err.println("[SdkModuleHost] Error in wildcard listener: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Dispatch a raw JSON message from RabbitMQ to registered SDK modules.
     */
    public void dispatch(JSONObject json) {
        try {
            String messageType = json.optString("message_type");
            String eventType = mapMessageTypeToEventType(messageType);

            if (eventType == null)
                return; // Unknown or irrelevant message

            Object payload = null;

            // Deserialize based on event type
            if ("INITIATE_MITIGATION".equals(eventType)) {
                payload = parseMitigationCommand(json);
            } else if ("ODL_TOPOLOGY_DISCOVER".equals(eventType)) {
                payload = json; // Pass full JSON
            } else if (eventType.startsWith("odl.")) {
                payload = json;
            }

            if (payload != null) {
                // Create SDK Event wrapper
                Event<Object> event = new Event<>(
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        eventType,
                        payload);

                // Dispatch to listeners
                api.dispatchLocal(event);
            }

        } catch (Exception e) {
            System.err.println("[SdkModuleHost] Failed to dispatch message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String mapMessageTypeToEventType(String messageType) {
        if ("odl.host.isolate".equals(messageType))
            return "INITIATE_MITIGATION"; // Map workflow command to SDK event
        if ("workflow_command".equals(messageType))
            return "INITIATE_MITIGATION"; // Workflow commands from CommandRoutingListener
        if ("workflow.command".equals(messageType))
            return "INITIATE_MITIGATION"; // Generic mitigation legacy
        if ("odl.topology.discover".equals(messageType))
            return "ODL_TOPOLOGY_DISCOVER";
        if (messageType != null && messageType.startsWith("odl."))
            return messageType.toUpperCase();
        return null;
    }

    private MitigationCommandData parseMitigationCommand(JSONObject json) {
        JSONObject payload = json.optJSONObject("payload");
        if (payload == null)
            return null;

        String ip = payload.optString("ip_address", payload.optString("targetHost"));
        String reason = payload.optString("reason", payload.optString("justification", "Automated mitigation"));

        // Determine action from payload or message type
        MitigationAction action = MitigationAction.ISOLATE_VLAN; // Default for isolation

        // Check if action is specified in payload
        String actionStr = payload.optString("action", "");
        if ("ISOLATE_VLAN".equals(actionStr) || "ISOLATE".equals(actionStr)) {
            action = MitigationAction.ISOLATE_VLAN;
        } else if ("BLOCK_IP".equals(actionStr)) {
            action = MitigationAction.BLOCK_IP;
        } else if ("QUARANTINE".equals(actionStr)) {
            action = MitigationAction.QUARANTINE;
        } else {
            // Fall back to message type check
            String msgType = json.optString("message_type");
            if (msgType.contains("isolate")) {
                action = MitigationAction.ISOLATE_VLAN;
            }
        }

        MitigationCommandData data = new MitigationCommandData(ip, action, reason);
        data.setWorkflowInstanceId(json.optString("event_id"));

        return data;
    }

    /**
     * Set the module registry for auto-registration
     */
    public void setModuleRegistry(ModuleRegistry registry) {
        this.registry = registry;
    }

    public void initRabbitMq() {
        api.initRabbitMq();
    }

    public void initializeModules() {
        // Initialize all SDK-based embedded modules
        initializeSingleModule("com.nis1.thesis.udm.OpenDaylightModule", api);
        initializeSingleModule("com.nis1.thesis.udm.SuricataHttpModule", api);
        initializeSingleModule("com.nis1.thesis.udm.ZeekHttpModule", api);
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

            // Auto-register with ModuleRegistry if available
            if (registry != null) {
                registerSdkModuleWithRegistry(module, className);
            }

        } catch (ClassNotFoundException e) {
            System.out.println("[SdkModuleHost] SDK module class not found on classpath: " + className);
        } catch (Throwable t) {
            System.err.println("[SdkModuleHost] Failed to initialize SDK module " + className + ": " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Register SDK module with ModuleRegistry
     */
    private void registerSdkModuleWithRegistry(PluggableModule module, String className) {
        try {
            // Get capabilities from API subscriptions
            List<String> capabilities = api.getCapabilities();

            // Create registration message
            JSONObject registration = new JSONObject();
            registration.put("message_type", "module.register");

            JSONObject payload = new JSONObject();
            String moduleId = "sdk-" + className.substring(className.lastIndexOf('.') + 1).toLowerCase();
            payload.put("module_id", moduleId);
            payload.put("module_name", module.getName());
            payload.put("module_type", "SDK");
            payload.put("capabilities", new JSONArray(capabilities));
            payload.put("command_queue", moduleId + "_commands"); // SDK modules use in-memory dispatch

            JSONObject metadata = new JSONObject();
            metadata.put("class_name", className);
            metadata.put("runtime", "embedded");
            payload.put("metadata", metadata);

            registration.put("payload", payload);

            // Register with ModuleRegistry
            registry.registerModule(registration);

            System.out.println("[SdkModuleHost] ✅ Registered " + moduleId + " with capabilities: " + capabilities);

        } catch (Exception e) {
            System.err.println("[SdkModuleHost] Failed to register module with registry: " + e.getMessage());
            e.printStackTrace();
        }
    }

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
    }
}
