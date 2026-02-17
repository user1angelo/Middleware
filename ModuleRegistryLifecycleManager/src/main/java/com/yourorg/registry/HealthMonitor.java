package com.yourorg.registry;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * HealthMonitor - Monitors UDM health via heartbeats
 * 
 * Features:
 * - Periodic health checks (default every 30 seconds)
 * - Marks modules offline if no heartbeat for 120 seconds
 * - Automatic recovery when heartbeat resumes
 * - Thread-safe operation
 */
public class HealthMonitor implements Runnable {

    private final ModuleRegistry registry;
    private volatile boolean running = true;

    public HealthMonitor(ModuleRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run() {
        int checkIntervalSeconds = ConfigLoader.getHealthCheckIntervalSeconds();
        int heartbeatTimeoutSeconds = ConfigLoader.getHeartbeatTimeoutSeconds();

        System.out.println("💓 HealthMonitor started");
        System.out.println("   Check interval: " + checkIntervalSeconds + " seconds");
        System.out.println("   Heartbeat timeout: " + heartbeatTimeoutSeconds + " seconds");

        while (running) {
            try {
                Thread.sleep(checkIntervalSeconds * 1000L);
                checkModuleHealth(heartbeatTimeoutSeconds);

            } catch (InterruptedException e) {
                System.out.println("⚠️  HealthMonitor interrupted");
                break;
            } catch (Exception e) {
                System.err.println("❌ HealthMonitor error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("💔 HealthMonitor stopped");
    }

    /**
     * Check health of all registered modules
     */
    private void checkModuleHealth(int timeoutSeconds) {
        Map<String, ModuleRegistry.RegisteredModule> modules = registry.getAllModules();

        if (modules.isEmpty()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        int onlineCount = 0;
        int offlineCount = 0;

        for (ModuleRegistry.RegisteredModule module : modules.values()) {
            // Skip SDK modules - they run in-memory and don't send heartbeats
            boolean isSdkModule = module.getMetadata() != null &&
                    "embedded".equals(module.getMetadata().optString("runtime"));

            if (isSdkModule) {
                // SDK modules are always "online" if they're registered
                onlineCount++;
                continue;
            }

            Timestamp lastHeartbeat = module.getLastHeartbeat();
            long timeSinceHeartbeat = currentTime - lastHeartbeat.getTime();
            long timeSinceHeartbeatSeconds = timeSinceHeartbeat / 1000;

            if ("online".equals(module.getStatus())) {
                // Check if module should be marked offline
                if (timeSinceHeartbeatSeconds > timeoutSeconds) {
                    registry.markModuleOffline(module.getModuleId());
                    offlineCount++;

                    System.out.println("🔴 Module timeout: " + module.getModuleId() +
                            " (last heartbeat " + timeSinceHeartbeatSeconds + "s ago)");
                } else {
                    onlineCount++;
                }
            } else {
                offlineCount++;
            }
        }

        // Log health summary periodically (every 10 checks)
        if (Math.random() < 0.1) { // Approximately every 10 checks
            System.out.println("💚 Health check: " + onlineCount + " online, " +
                    offlineCount + " offline");
        }
    }

    /**
     * Stop the health monitor
     */
    public void stop() {
        running = false;
    }
}
