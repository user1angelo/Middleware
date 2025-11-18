package com.yourorg.odl.enforcer.impl;

import com.rabbitmq.client.*;
import com.yourorg.odl.enforcer.api.CommandHandler;
import com.yourorg.odl.enforcer.api.WorkflowCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Listens to workflow_command_queue and routes commands to appropriate handlers.
 */
public class WorkflowCommandListener {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowCommandListener.class);
    
    private final ConfigLoader config;
    private final List<CommandHandler> handlers;
    private Connection connection;
    private Channel channel;
    private volatile boolean running = true;
    
    public WorkflowCommandListener(ConfigLoader config, List<CommandHandler> handlers) {
        this.config = config;
        this.handlers = handlers;
    }
    
    public void start() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.getRabbitMQHost());
        factory.setPort(config.getRabbitMQPort());
        factory.setUsername(config.getRabbitMQUser());
        factory.setPassword(config.getRabbitMQPassword());
        
        connection = factory.newConnection();
        channel = connection.createChannel();
        
        String queueName = config.getWorkflowCommandQueue();
        channel.queueDeclare(queueName, true, false, false, null);
        channel.basicQos(1); // Process one message at a time
        
        LOG.info("Starting WorkflowCommandListener on queue: {}", queueName);
        
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            LOG.info("Received workflow command: {}", message);
            
            boolean handled = false;
            try {
                WorkflowCommand command = WorkflowCommand.fromJson(message);
                LOG.info("Processing command: {}", command);
                
                // Route to appropriate handler
                for (CommandHandler handler : handlers) {
                    if (handler.canHandle(command.getMessageType())) {
                        handled = handler.handleCommand(command);
                        if (handled) {
                            LOG.info("Command handled successfully by {}", handler.getClass().getSimpleName());
                            break;
                        }
                    }
                }
                
                if (!handled) {
                    LOG.warn("No handler found for command type: {}", command.getMessageType());
                }
                
                // ACK the message
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                
            } catch (Exception e) {
                LOG.error("Error processing workflow command: {}", e.getMessage(), e);
                // NACK and requeue on error
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            }
        };
        
        channel.basicConsume(queueName, false, deliverCallback, consumerTag -> {
            LOG.info("Consumer cancelled: {}", consumerTag);
        });
        
        LOG.info("WorkflowCommandListener started successfully");
    }
    
    public void stop() {
        running = false;
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
            LOG.info("WorkflowCommandListener stopped");
        } catch (IOException | TimeoutException e) {
            LOG.error("Error stopping listener: {}", e.getMessage(), e);
        }
    }
}
