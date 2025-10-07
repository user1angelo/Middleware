package com.yourorg.workflow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main application entry point for WorkflowEngine.
 * Starts WorkflowQueueListener in a separate thread with graceful shutdown handling.
 * 
 * Similar architecture to ThreatContextStoreMain for consistency.
 */
public class WorkflowEngineMain {
    
    private static final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private static volatile boolean running = true;
    
    public static void main(String[] args) {
        System.out.println("🚀 Starting WorkflowEngine");
        System.out.println("================================================");
        System.out.println("Purpose: Automated ransomware response orchestration");
        System.out.println("Listens to: workflow_queue");
        System.out.println("Publishes to: workflow_response_queue");
        System.out.println("================================================\n");
        
        // Add shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Shutdown signal received...");
            running = false;
            executorService.shutdownNow();
            System.out.println("✅ WorkflowEngine stopped gracefully");
        }));
        
        try {
            // Start Workflow Queue Listener in separate thread
            executorService.submit(() -> {
                try {
                    System.out.println("📡 Starting Workflow Queue Listener thread...\n");
                    WorkflowQueueListener listener = new WorkflowQueueListener();
                    listener.start();
                } catch (Exception e) {
                    System.err.println("❌ Workflow Queue Listener failed:");
                    e.printStackTrace();
                }
            });
            
            System.out.println("✅ Listener thread started successfully");
            System.out.println("   Press CTRL+C to stop WorkflowEngine\n");
            
            // Keep main thread alive
            Thread.currentThread().join();
            
        } catch (Exception e) {
            System.err.println("❌ Fatal error in WorkflowEngine:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}

