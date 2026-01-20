package com.yourorg.registry;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.PluggableModule;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * SdkModuleHost
 *
 * Lightweight host that initializes SDK-based pluggable modules which are
 * already present on the JVM classpath (e.g. user-defined-modules/*.jar).
 *
 * This is intentionally minimal: it provides a stub CoreSystemApi that logs
 * publish/subscribe activity so we have runtime evidence that modules like
 * OpenDaylightModule are actually being loaded and initialized. It does NOT
 * yet integrate with RabbitMQ or the JSON-based ModuleRegistry messaging
 * pipeline.
 */
public class SdkModuleHost {

    private final Map<String, PluggableModule> activeModules = new HashMap<>();

    /**
     * Simple CoreSystemApi stub that just logs publish/subscribe calls.
     */
    private static class LoggingCoreSystemApi implements CoreSystemApi {
        private final Map<String, Consumer<Event<?>>> listeners = new HashMap<>();

        @Override
        public void publishEvent(Event<?> event) {
            System.out.println("[SdkModuleHost][CoreSystemApi] publishEvent type=" + event.getType()
                    + " id=" + event.getId());
        }

        @Override
        public void subscribeToEvent(String eventType, Consumer<Event<?>> listener) {
            System.out.println("[SdkModuleHost][CoreSystemApi] subscribeToEvent pattern=" + eventType);
            listeners.put(eventType, listener);
        }

        public Map<String, Consumer<Event<?>>> getListeners() {
            return listeners;
        }
    }

    /**
     * Initialize all SDK-based modules that we know about on the current
     * classpath. For now this is explicitly wired to OpenDaylightModule so
     * that we can prove opendaylight-module.jar is being loaded and run.
     */
    public void initializeModules() {
        LoggingCoreSystemApi api = new LoggingCoreSystemApi();

        // NOTE: This relies on the opendaylight-module JAR and the SDK
        // classes being present on the Java classpath when
        // ModuleRegistryMain is started. The web backend has been updated
        // to include ../nis-thesis-sdk/out and ../user-defined-modules/*
        // on the -cp for the ModuleRegistry process.
        initializeSingleModule("com.nis1.thesis.udm.OpenDaylightModule", api);

        System.out.println("[SdkModuleHost] Active SDK modules: " + activeModules.keySet());
    }

    private void initializeSingleModule(String className, CoreSystemApi api) {
        try {
            Class<?> clazz = Class.forName(className);
            if (!PluggableModule.class.isAssignableFrom(clazz)) {
                System.out.println("[SdkModuleHost] Class " + className + " does not implement PluggableModule; skipping.");
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
                System.err.println("[SdkModuleHost] Error during shutdown of " + entry.getKey() + ": " + t.getMessage());
            }
        }
        activeModules.clear();
    }
}
