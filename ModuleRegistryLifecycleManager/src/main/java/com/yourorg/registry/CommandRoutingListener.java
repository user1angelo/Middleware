package com.yourorg.registry;

import com.rabbitmq.client.*;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * CommandRoutingListener - Routes workflow execution commands to UDMs
 * 
 * Flow:
 * 1. Listens to workflow_response_queue for commands from WorkflowEngine
 * 2. Extracts command and target_module from message
 * 3. Looks up module's command_queue in registry
 * 4. Routes command to appropriate UDM queue
 * 
 * Handles workflow commands like:
 * - sdn_isolate
 * - firewall_block
 * - quarantine
 * - terminate_process
 * - etc.
 */
public class CommandRoutingListener implements Runnable {
    
    private final ModuleRegistry registry;
    private volatile boolean running = true;
    
    public CommandRoutingListener(ModuleRegistry registry) {
        this.registry = registry;
    }
    
    @Override
    public void run() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(ConfigLoader.getRabbitMQHost());
        factory.setPort(ConfigLoader.getRabbitMQPort());
        factory.setUsername(ConfigLoader.getRabbitMQUser());
        factory.setPassword(ConfigLoader.getRabbitMQPassword());
        
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            
            String workflowResponseQueue = ConfigLoader.getWorkflowResponseQueueName();
            
            // Declare queue
            channel.queueDeclare(workflowResponseQueue, true, false, false, null);
            
            System.out.println("🎯 CommandRoutingListener started");
            System.out.println("   Listening on: " + workflowResponseQueue);
            System.out.println("   Routing commands to registered UDM queues");
            
            // Set up consumer
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                try {
                    String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    routeCommand(message, channel);
                    
                    // Acknowledge message
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    
                } catch (Exception e) {
                    System.err.println("❌ Error routing command: " + e.getMessage());
                    e.printStackTrace();
                    
                    // Reject and requeue on error
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                }
            };
            
            channel.basicConsume(workflowResponseQueue, false, deliverCallback, consumerTag -> {});
            
            // Keep running
            while (running) {
                Thread.sleep(1000);
            }
            
        } catch (Exception e) {
            System.err.println("❌ CommandRoutingListener error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Route command to appropriate UDM
     */
    private void routeCommand(String message, Channel channel) throws IOException {
        try {
            JSONObject json = new JSONObject(message);
            String messageType = json.optString("message_type", "unknown");
            
            // Only process workflow commands
            if (!"workflow_command".equals(messageType)) {
                System.out.println("⚠️  Non-command message type: " + messageType);
                return;
            }
            
            // Extract command from event_type or payload
            String eventType = json.optString("event_type", "unknown");
            JSONObject payload = json.getJSONObject("payload");
            
            // Use event_type as the command (e.g., INITIATE_MITIGATION)
            String command = eventType;
            
            // Allow override via explicit command field in payload (for backwards compatibility)
            if (payload.has("command")) {
                command = payload.getString("command");
            }
            
            String targetModule = payload.optString("target_module", null);
            
            System.out.println("🎯 Routing command: " + command);
            
            // Determine target module
            ModuleRegistry.RegisteredModule module;
            if (targetModule != null) {
                // Direct module targeting
                module = registry.getModule(targetModule);
                if (module == null) {
                    System.err.println("❌ Target module not found: " + targetModule);
                    return;
                }
            } else {
                // Capability-based routing
                module = registry.findModuleByCapability(command);
                if (module == null) {
                    System.err.println("❌ No module found with capability: " + command);
                    return;
                }
            }
            
            // Check if module is online
            if (!"online".equals(module.getStatus())) {
                System.err.println("❌ Module is offline: " + module.getModuleId());
                return;
            }
            
            // Get the module's command queue
            String commandQueue = module.getCommandQueue();
            
            // Declare the module's queue if it doesn't exist
            channel.queueDeclare(commandQueue, true, false, false, null);
            
            // Send command to module's queue
            byte[] messageBytes = json.toString().getBytes(StandardCharsets.UTF_8);
            channel.basicPublish("", commandQueue, null, messageBytes);
            
            System.out.println("✅ Routed command '" + command + "' to module '" + 
                             module.getModuleId() + "' via queue '" + commandQueue + "'");
            
        } catch (Exception e) {
            System.err.println("❌ Failed to route command: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Stop the listener
     */
    public void stop() {
        running = false;
    }
}

