package com.nis1.thesis.sdk;

/**
 * Contract interface that all pluggable security modules must implement.
 * <p>
 * This interface defines the lifecycle management contract for modules within the SOAR framework.
 * All security processing modules (threat intelligence, detection, response, etc.) implement this
 * interface to integrate with the core system.
 * </p>
 * <p>
 * The framework guarantees that {@link #initialize(CoreSystemApi)} will be called exactly once
 * before the module is expected to be operational, and {@link #shutdown()} will be called
 * during graceful system shutdown for resource cleanup.
 * </p>
 *
 * @author NIS1
 * @version 1.0
 * @since September 10, 2025
 */
public interface PluggableModule {
    /**
     * Returns a stable, human-readable name for this module.
     * <p>
     * This name is used throughout the system for logging, diagnostics, and identification
     * purposes. It should be descriptive and unique within the deployed modules.
     * </p>
     * 
     * @return A stable, human-readable name for logging and diagnostics
     */
    String getName();

    /**
     * Initializes the module with access to the core system API.
     * <p>
     * This method is called exactly once during module lifecycle, before the module
     * is expected to be operational. The module should perform all necessary resource
     * initialization, configuration loading, and event subscriptions during this call.
     * </p>
     * <p>
     * The provided API handle allows the module to publish events and subscribe to
     * event patterns for reactive processing.
     * </p>
     * 
     * @param api The core system API for publishing events and subscribing to event patterns
     * @throws RuntimeException if initialization fails and the module cannot operate
     */
    void initialize(CoreSystemApi api);

    /**
     * Gracefully shuts down the module and cleans up all allocated resources.
     * <p>
     * This method is called during system shutdown to allow modules to properly
     * release resources, close connections, persist state, and perform other cleanup
     * operations. Implementations should be defensive and handle partial initialization states.
     * </p>
     * <p>
     * After this method returns, the module will no longer receive events and should
     * not attempt to publish new events.
     * </p>
     */
    void shutdown();
}
