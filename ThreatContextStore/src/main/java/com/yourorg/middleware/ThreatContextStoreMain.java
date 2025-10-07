package com.yourorg.middleware;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main application that runs RabbitMQListener and AlertProcessor as separate threads
 */
public class ThreatContextStoreMain {
    
    private static final ExecutorService executorService = Executors.newFixedThreadPool(2);
    private static volatile boolean running = true;
    
    public static void main(String[] args) {
        System.out.println("🚀 Starting ThreatContextStore Main Application");
        System.out.println("================================================");
        
        // Add shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Shutdown signal received...");
            running = false;
            executorService.shutdownNow();
            System.out.println("✅ ThreatContextStore stopped gracefully");
        }));
        
        try {
            // Start RabbitMQ Listener in separate thread
            executorService.submit(() -> {
                try {
                    System.out.println("📡 Starting RabbitMQ Listener thread...");
                    RabbitMQListener listener = new RabbitMQListener();
                    listener.start();
                } catch (Exception e) {
                    System.err.println("❌ RabbitMQ Listener failed:");
                    e.printStackTrace();
                }
            });
            
            // Start File Watcher AlertProcessor in separate thread
            executorService.submit(() -> {
                try {
                    System.out.println("👁️  Starting File Watcher AlertProcessor thread...");
                    startFileWatcherAlertProcessor();
                } catch (Exception e) {
                    System.err.println("❌ AlertProcessor failed:");
                    e.printStackTrace();
                }
            });
            
            System.out.println("\n✅ All threads started successfully");
            System.out.println("   Press CTRL+C to stop all services\n");
            
            // Keep main thread alive
            Thread.currentThread().join();
            
        } catch (Exception e) {
            System.err.println("❌ Fatal error in main application:");
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * File watcher that continuously monitors messages/ directory for new JSON files
     */
    private static void startFileWatcherAlertProcessor() throws Exception {
        String messagesPath = ConfigLoader.getMessagesPath();
        File messagesDir = new File(messagesPath);
        
        if (!messagesDir.exists()) {
            messagesDir.mkdirs();
            System.out.println("📁 Created messages directory: " + messagesPath);
        }
        
        // Track processed files to avoid reprocessing
        Set<String> processedFiles = new HashSet<>();
        
        // Process existing files first
        System.out.println("📂 Processing existing files in: " + messagesPath);
        processExistingFiles(messagesDir, processedFiles);
        
        // Setup file watcher for new files
        Path path = Paths.get(messagesPath);
        WatchService watchService = FileSystems.getDefault().newWatchService();
        path.register(watchService, 
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY);
        
        System.out.println("👁️  Watching for new files in: " + messagesPath);
        
        // RabbitMQ connection for sending messages
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(ConfigLoader.getRabbitMqHost());
        factory.setPort(ConfigLoader.getRabbitMqPort());
        factory.setUsername(ConfigLoader.getRabbitMqUser());
        factory.setPassword(ConfigLoader.getRabbitMqPassword());
        
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            
            String queueName = ConfigLoader.getRabbitMqQueueName();
            channel.queueDeclare(queueName, true, false, false, null);
            
            // Watch for file changes
            WatchKey key;
            while (running && (key = watchService.take()) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path filename = ev.context();
                    File file = new File(messagesDir, filename.toString());
                    
                    // Only process .json files that haven't been processed
                    if (file.getName().endsWith(".json") && !processedFiles.contains(file.getName())) {
                        // Small delay to ensure file is fully written
                        Thread.sleep(100);
                        
                        if (file.exists() && file.length() > 0) {
                            processFile(file, channel, queueName);
                            processedFiles.add(file.getName());
                        }
                    }
                }
                key.reset();
            }
        }
    }
    
    /**
     * Process existing files in the directory at startup
     */
    private static void processExistingFiles(File directory, Set<String> processedFiles) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(ConfigLoader.getRabbitMqHost());
        factory.setPort(ConfigLoader.getRabbitMqPort());
        factory.setUsername(ConfigLoader.getRabbitMqUser());
        factory.setPassword(ConfigLoader.getRabbitMqPassword());
        
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            
            String queueName = ConfigLoader.getRabbitMqQueueName();
            channel.queueDeclare(queueName, true, false, false, null);
            
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null && files.length > 0) {
                System.out.println("📤 Found " + files.length + " existing files to process");
                for (File file : files) {
                    processFile(file, channel, queueName);
                    processedFiles.add(file.getName());
                }
            } else {
                System.out.println("📭 No existing files found in messages directory");
            }
        }
    }
    
    /**
     * Process a single JSON file and send to RabbitMQ
     */
    private static void processFile(File file, Channel channel, String queueName) {
        try {
            String content = Files.readString(file.toPath());
            JSONObject json = new JSONObject(content);
            
            // Ensure message_type is present (default to "alert")
            if (!json.has("message_type")) {
                json.put("message_type", "alert");
            }
            
            channel.basicPublish("", queueName, null, json.toString().getBytes("UTF-8"));
            System.out.println("✅ Sent " + file.getName() + " to RabbitMQ (type: " + json.getString("message_type") + ")");
            
        } catch (Exception e) {
            System.err.println("⚠️  Failed to process " + file.getName() + ": " + e.getMessage());
        }
    }
}

