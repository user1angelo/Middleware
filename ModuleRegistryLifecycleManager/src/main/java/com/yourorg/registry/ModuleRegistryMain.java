package com.yourorg.registry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ModuleRegistryMain - Central hub for User-Defined Module management
 * 
 * This is the main entry point that coordinates all ModuleRegistry components:
 * - ModuleRegistry: Tracks registered modules with database persistence
 * - AlertBroadcastListener: Receives UDM alerts and broadcasts to both queues
 * - CommandRoutingListener: Routes workflow commands to appropriate UDMs
 * - HealthMonitor: Monitors UDM heartbeats and health status
 * 
 * Architecture:
 * UDM → workflow_queue → AlertBroadcastListener → alerts_queue + workflow_queue
 * WorkflowEngine → workflow_response_queue → CommandRoutingListener → UDM
 * queues
 * 
 * Usage:
 * java -cp "out:lib/*" com.yourorg.registry.ModuleRegistryMain
 */
public class ModuleRegistryMain {

    private static ModuleRegistry registry;
    private static AlertBroadcastListener alertListener;
    private static CommandRoutingListener commandListener;
    private static HealthMonitor healthMonitor;
    private static ExecutorService executorService;
    private static SdkModuleHost sdkModuleHost;

    public static void main(String[] args) {
        printBanner();

        // Initialize registry
        System.out.println("\n🔧 Initializing ModuleRegistry...");
        registry = new ModuleRegistry();

        // Load existing modules from database (and scan filesystem/JARs)
        System.out.println("📂 Loading modules from database...");
        registry.loadModulesFromDatabase();

        // Initialize SDK-based pluggable modules (e.g., OpenDaylightModule)
        System.out.println("\n🔌 Initializing SDK-based modules from classpath...");
        sdkModuleHost = new SdkModuleHost();
        sdkModuleHost.initializeModules();

        // Create component instances
        System.out.println("\n🚀 Starting components...\n");
        alertListener = new AlertBroadcastListener(registry, sdkModuleHost);
        commandListener = new CommandRoutingListener(registry);
        healthMonitor = new HealthMonitor(registry);

        // Create thread pool for components
        executorService = Executors.newFixedThreadPool(3);

        // Start all components in separate threads
        executorService.submit(alertListener);
        executorService.submit(commandListener);
        executorService.submit(healthMonitor);

        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n\n🛑 Shutdown signal received...");
            shutdown();
        }));

        System.out.println("\n✅ ModuleRegistryAndLifecycleManager is running");
        System.out.println("================================================");
        System.out.println("Press CTRL+C to stop\n");

        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("Main thread interrupted");
            shutdown();
        }
    }

    /**
     * Print application banner
     */
    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║  ModuleRegistry & Lifecycle Manager v1.0      ║");
        System.out.println("╚════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Purpose: Central hub for User-Defined Module management");
        System.out.println("- Registers and tracks UDMs");
        System.out.println("- Broadcasts alerts from UDMs to system queues");
        System.out.println("- Routes workflow commands to appropriate UDMs");
        System.out.println("- Monitors UDM health and availability");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  RabbitMQ: " + ConfigLoader.getRabbitMQHost() + ":" + ConfigLoader.getRabbitMQPort());
        System.out.println("  Database: " + ConfigLoader.getDbHost() + ":" + ConfigLoader.getDbPort() + "/"
                + ConfigLoader.getDbName());
        System.out.println("  Workflow Queue: " + ConfigLoader.getWorkflowQueueName());
        System.out.println("  Alerts Queue: " + ConfigLoader.getAlertsQueueName());
        System.out.println("  Response Queue: " + ConfigLoader.getWorkflowResponseQueueName());
    }

    /**
     * Graceful shutdown of all components
     */
    private static void shutdown() {
        System.out.println("🛑 Shutting down ModuleRegistry components...");

        // Stop listeners
        if (alertListener != null) {
            alertListener.stop();
            System.out.println("  ✓ AlertBroadcastListener stopped");
        }

        if (commandListener != null) {
            commandListener.stop();
            System.out.println("  ✓ CommandRoutingListener stopped");
        }

        if (healthMonitor != null) {
            healthMonitor.stop();
            System.out.println("  ✓ HealthMonitor stopped");
        }

        // Shut down SDK-based modules
        if (sdkModuleHost != null) {
            sdkModuleHost.shutdownModules();
            System.out.println("  ✓ SDK-based modules shutdown complete");
        }

        // Shutdown executor service
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
                System.out.println("  ✓ Thread pool shutdown complete");
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("\n✅ ModuleRegistry shutdown complete. Goodbye!");
    }
}
