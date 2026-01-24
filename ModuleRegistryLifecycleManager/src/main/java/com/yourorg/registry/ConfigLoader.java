package com.yourorg.registry;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * ConfigLoader - Centralized configuration management for ModuleRegistry
 * 
 * Loads configuration from config.properties with sensible defaults
 * Handles RabbitMQ, PostgreSQL, and queue settings
 */
public class ConfigLoader {

    private static final String CONFIG_FILE = "config.properties";
    private static Properties properties = new Properties();

    static {
        loadConfig();
    }

    /**
     * Load configuration from file with fallback to defaults
     */
    private static void loadConfig() {
        try (InputStream input = new FileInputStream(CONFIG_FILE)) {
            properties.load(input);
            System.out.println("✅ Loaded configuration from " + CONFIG_FILE);
        } catch (IOException e) {
            System.out.println("⚠️  Config file not found, using default values");
            loadDefaults();
        }
    }

    /**
     * Load default configuration values
     */
    private static void loadDefaults() {
        // Database defaults
        properties.setProperty("db.host", "192.168.86.28");
        properties.setProperty("db.port", "5432");
        properties.setProperty("db.name", "wazuhdb");
        properties.setProperty("db.user", "postgres");
        properties.setProperty("db.password", "postgres");

        // RabbitMQ defaults
        properties.setProperty("rabbitmq.host", "192.168.86.76");
        properties.setProperty("rabbitmq.port", "5672");
        properties.setProperty("rabbitmq.user", "user");
        properties.setProperty("rabbitmq.password", "password");

        // Queue names
        properties.setProperty("rabbitmq.workflow_queue.name", "workflow_queue");
        properties.setProperty("rabbitmq.alerts_queue.name", "alerts_queue");
        properties.setProperty("rabbitmq.workflow_response_queue.name", "workflow_response_queue");

        // Health monitoring
        properties.setProperty("health.heartbeat_timeout_seconds", "120");
        properties.setProperty("health.check_interval_seconds", "30");

        // User-defined modules
        properties.setProperty("modules.root", "../user-defined-modules");
    }

    // Database configuration getters
    public static String getDbHost() {
        return properties.getProperty("db.host", "192.168.86.28");
    }

    public static int getDbPort() {
        return Integer.parseInt(properties.getProperty("db.port", "5432"));
    }

    public static String getDbName() {
        return properties.getProperty("db.name", "wazuhdb");
    }

    public static String getDbUser() {
        return properties.getProperty("db.user", "postgres");
    }

    public static String getDbPassword() {
        return properties.getProperty("db.password", "postgres");
    }

    public static String getDbUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s",
                getDbHost(), getDbPort(), getDbName());
    }

    // RabbitMQ configuration getters
    public static String getRabbitMQHost() {
        return properties.getProperty("rabbitmq.host", "192.168.86.76");
    }

    public static int getRabbitMQPort() {
        return Integer.parseInt(properties.getProperty("rabbitmq.port", "5672"));
    }

    public static String getRabbitMQUser() {
        return properties.getProperty("rabbitmq.user", "user");
    }

    public static String getRabbitMQPassword() {
        return properties.getProperty("rabbitmq.password", "password");
    }

    // Queue names
    public static String getWorkflowQueueName() {
        return properties.getProperty("rabbitmq.workflow_queue.name", "workflow_queue");
    }

    public static String getAlertsQueueName() {
        return properties.getProperty("rabbitmq.alerts_queue.name", "alerts_queue");
    }

    public static String getWorkflowResponseQueueName() {
        return properties.getProperty("rabbitmq.workflow_response_queue.name", "workflow_response_queue");
    }

    // Health monitoring configuration
    public static int getHeartbeatTimeoutSeconds() {
        return Integer.parseInt(properties.getProperty("health.heartbeat_timeout_seconds", "120"));
    }

    public static int getHealthCheckIntervalSeconds() {
        return Integer.parseInt(properties.getProperty("health.check_interval_seconds", "30"));
    }

    // User-defined modules root directory (for scanning configs or modules on disk)
    public static String getModulesRoot() {
        return properties.getProperty("modules.root", "../user-defined-modules");
    }
}
