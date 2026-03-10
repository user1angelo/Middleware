package com.yourorg.odl.enforcer.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Central configuration loader for ODL Network Enforcer.
 * Reads from config.properties with fallback to defaults.
 */
public class ConfigLoader {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String CONFIG_FILE = "config.properties";

    private final Properties properties;

    public ConfigLoader() {
        this.properties = new Properties();
        loadProperties();
    }

    private void loadProperties() {
        // Try to load from file
        try (InputStream input = new FileInputStream(CONFIG_FILE)) {
            properties.load(input);
            LOG.info("Loaded configuration from {}", CONFIG_FILE);
        } catch (IOException e) {
            LOG.warn("Could not load {} - using defaults: {}", CONFIG_FILE, e.getMessage());
        }
    }

    // RabbitMQ Configuration
    public String getRabbitMQHost() {
        return properties.getProperty("rabbitmq.host", "127.0.0.1");
    }

    public int getRabbitMQPort() {
        return Integer.parseInt(properties.getProperty("rabbitmq.port", "5672"));
    }

    public String getRabbitMQUser() {
        return properties.getProperty("rabbitmq.user", "user");
    }

    public String getRabbitMQPassword() {
        return properties.getProperty("rabbitmq.password", "password");
    }

    public String getWorkflowCommandQueue() {
        return properties.getProperty("rabbitmq.workflow_command_queue.name", "workflow_command_queue");
    }

    // ODL Configuration
    public String getODLHost() {
        return properties.getProperty("odl.host", "localhost");
    }

    public int getODLPort() {
        return Integer.parseInt(properties.getProperty("odl.port", "8181"));
    }

    public String getODLUser() {
        return properties.getProperty("odl.user", "admin");
    }

    public String getODLPassword() {
        return properties.getProperty("odl.password", "admin");
    }

    // Flow Configuration
    public int getDefaultFlowPriority() {
        return Integer.parseInt(properties.getProperty("flow.default.priority", "100"));
    }

    public int getIsolationFlowPriority() {
        return Integer.parseInt(properties.getProperty("flow.isolation.priority", "1000"));
    }

    public int getFlowIdleTimeout() {
        return Integer.parseInt(properties.getProperty("flow.idle.timeout", "0"));
    }

    public int getFlowHardTimeout() {
        return Integer.parseInt(properties.getProperty("flow.hard.timeout", "0"));
    }
}
