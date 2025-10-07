package com.yourorg.workflow;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration loader for WorkflowEngine.
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
    
    public static String getWorkflowQueueName() {
        return get("rabbitmq.workflow_queue.name", "workflow_queue");
    }
    
    public static String getWorkflowResponseQueueName() {
        return get("rabbitmq.workflow_response_queue.name", "workflow_response_queue");
    }
    
    // Workflow configuration
    public static String getWorkflowsDirectory() {
        return get("workflows.directory", "workflows/ransomware");
    }
}

