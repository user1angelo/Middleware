package com.yourorg.middleware;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration loader for middleware application.
 * Reads from config.properties file with fallback to default values.
 */
public class ConfigLoader {
    private static final Properties properties = new Properties();
    private static final String CONFIG_FILE = "config.properties";

    static {
        loadProperties();
    }

    private static void loadProperties() {
        try (InputStream input = new FileInputStream(CONFIG_FILE)) {
            properties.load(input);
            System.out.println("✅ Loaded configuration from " + CONFIG_FILE);
        } catch (IOException e) {
            System.out.println("⚠ config.properties not found, using default values");
        }
    }

    public static String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    // Database configuration
    public static String getDbHost() {
        return get("db.host", "192.168.86.28");
    }

    public static int getDbPort() {
        return Integer.parseInt(get("db.port", "5432"));
    }

    public static String getDbName() {
        return get("db.name", "wazuhdb");
    }

    public static String getDbUser() {
        return get("db.user", "postgres");
    }

    public static String getDbPassword() {
        return get("db.password", "postgres");
    }

    public static String getDbUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s",
                getDbHost(), getDbPort(), getDbName());
    }

    // RabbitMQ configuration
    public static String getRabbitMqHost() {
        return get("rabbitmq.host", "192.168.86.76");
    }

    public static int getRabbitMqPort() {
        return Integer.parseInt(get("rabbitmq.port", "5672"));
    }

    public static String getRabbitMqUser() {
        return get("rabbitmq.user", "guest");
    }

    public static String getRabbitMqPassword() {
        return get("rabbitmq.password", "guest");
    }

    public static String getRabbitMqQueueName() {
        return get("rabbitmq.queue.name", "alerts_queue");
    }

    public static String getRabbitMqQueryResponseQueueName() {
        return get("rabbitmq.query_response_queue.name", "query_response_queue");
    }

    // File paths configuration
    public static String getMessagesPath() {
        return get("paths.messages", "messages");
    }

    public static String getQueryResponsesPath() {
        return get("paths.query_responses", "query_responses");
    }
}
