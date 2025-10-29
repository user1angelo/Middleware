# SDK Detailed Context Documentation
## Security Orchestration and Automated Response (SOAR) Framework

**Version:** 2.0  
**Date:** December 2025  
**Target Audience:** Security Engineers, DevOps Teams, Integration Developers

---

## Table of Contents
1. [Executive Summary](#executive-summary)
2. [SDK Architecture Overview](#sdk-architecture-overview)
3. [Core Components](#core-components)
4. [Message Format Specification](#message-format-specification)
5. [Middleware Development Guide](#middleware-development-guide)
6. [Wazuh Integration Example](#wazuh-integration-example)
7. [Event Types and Routing](#event-types-and-routing)
8. [Implementation Patterns](#implementation-patterns)
9. [Security Considerations](#security-considerations)
10. [Troubleshooting Guide](#troubleshooting-guide)

---

## Executive Summary

The SOAR SDK enables the development of **middleware components** that facilitate seamless integration between security tools and the central orchestration framework. The SDK provides a standardized event-driven architecture that abstracts the complexity of inter-tool communication while maintaining high performance and reliability.

### Key Benefits
- **Standardized Communication**: All security tools communicate through a unified JSON message format
- **Loose Coupling**: Middleware components operate independently with fault-tolerant design
- **Scalable Architecture**: Event-driven design supports horizontal scaling
- **Tool Agnostic**: Integrates with any security tool that can send/receive HTTP requests or file outputs

---

## SDK Architecture Overview

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Security Tool │    │   Your Middleware│    │  SOAR Framework │
│   (e.g., Wazuh) │◄──►│   (SDK-based)    │◄──►│   (Core System) │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ Message Broker  │
                    │   (RabbitMQ)    │
                    └─────────────────┘
```

### Design Principles
1. **Event-Driven Architecture**: All communication occurs through standardized events
2. **Plugin-Based System**: Each tool integration is a self-contained pluggable module
3. **Policy as Code**: Decision logic is externalized to workflow files
4. **Fail-Safe Design**: Individual module failures don't affect the entire system

---

## Core Components

### 1. PluggableModule Interface
The foundation contract that every middleware component must implement:

```java
public interface PluggableModule {
    String getName();
    void initialize(CoreSystemApi api);
    void shutdown();
}
```

### 2. CoreSystemApi Interface
Provides communication capabilities with the framework:

```java
public interface CoreSystemApi {
    void publishEvent(Event<?> event);
    void subscribeToEvent(String eventType, Consumer<Event<?>> listener);
}
```

### 3. Event<T> Class
Generic event envelope for all inter-component communication:

```java
public final class Event<T> {
    private final String id;
    private final Instant timestamp;
    private final String type;
    private final T data;
    
    // Factory method for easy creation
    public static <T> Event<T> of(String type, T data) {
        return new Event<>(UUID.randomUUID().toString(), Instant.now(), type, data);
    }
}
```

---

## Message Format Specification

### Standard JSON Message Structure

All messages sent through the framework follow this standardized format:

```json
{
  "event_id": "e721dc1a-f34a-4c9e-ae82-1827b72e9a1e",
  "timestamp": "2025-07-21T10:35:12.452Z",
  "event_type": "alerts.host.wazuh",
  "source_module": "WazuhConnector",
  "payload": {
    "host_id": "host-192.168.1.101",
    "alert_type": "ransomware_detection",
    "signature_id": "9201021",
    "signature": "Suspicious file encryption activity",
    "severity": "high",
    "process": "C:\\Users\\John\\AppData\\Local\\Temp\\malware.exe",
    "file_path": "C:\\Users\\John\\Documents\\encrypted_file.docx",
    "matched_rule": "yara_ransomnote_heuristic",
    "source_ip": "192.168.1.101",
    "destination_ip": "10.0.0.5",
    "protocol": "TCP",
    "threat_score": 85
  }
}
```

### Field Specifications

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `event_id` | UUID String | Yes | Unique identifier for event tracking |
| `timestamp` | ISO 8601 DateTime | Yes | Event creation timestamp |
| `event_type` | String | Yes | Routing key for message delivery |
| `source_module` | String | No | Originating module identification |
| `payload` | Object | Yes | Tool-specific event data |

### Event Type Naming Convention

Event types follow a hierarchical dot-notation pattern:
- `alerts.host.wazuh` - Host-based alerts from Wazuh
- `alerts.network.suricata` - Network alerts from Suricata
- `mitigation.firewall.block` - Firewall blocking actions
- `enrichment.threat_intel.virustotal` - Threat intelligence data

---

## Middleware Development Guide

### Directory Structure and Naming Conventions for User-Defined Modules

**IMPORTANT**: All user-defined modules must be organized in a specific directory structure and follow naming conventions to ensure proper integration with the SOAR framework.

#### Required Directory Structure
User-defined modules must reside under the `nis1-thesis-udm` (User-Defined Modules) directory:

```
SOARapp/
├── nis1-thesis-udm/
│   └── src/main/java/com/nis1/thesis/udm/
│       ├── WazuhModule.java           # Wazuh integration module
│       ├── SplunkModule.java          # Splunk integration module
│       ├── FirewallModule.java        # Firewall automation module
│       └── ThreatIntelModule.java     # Threat intelligence module
├── nis1-thesis-core/                  # Core framework
├── nis-thesis-sdk/                    # SDK library
└── README.md
```

#### Naming Convention Requirements
1. **Module Files**: Must follow the pattern `*Module.java` (e.g., `WazuhModule.java`, `SplunkModule.java`)
2. **Package Structure**: Must use `com.nis1.thesis.udm` as the base package
3. **Class Names**: Should be descriptive and end with "Module" (e.g., `WazuhModule`, `SplunkModule`)

#### Benefits of This Structure
- **Easy Identification**: All user-defined modules are clearly organized
- **Namespace Isolation**: Prevents conflicts with core framework modules
- **Automated Discovery**: Framework can automatically discover and load modules
- **Consistent Development**: Standardized location for all integrations

### Step 1: Create Your Module Class

Create your user-defined module in the correct location:

```java
package com.nis1.thesis.udm;

import com.nis1.thesis.sdk.*;
import com.google.gson.Gson;
import okhttp3.*;

public class WazuhModule implements PluggableModule {
    private CoreSystemApi api;
    private ModuleHelper helper;
    private final Gson gson = new Gson();
    private final OkHttpClient httpClient = new OkHttpClient();
    
    @Override
    public String getName() {
        return "Wazuh Security Connector v2.0";
    }
    
    @Override
    public void initialize(CoreSystemApi api) {
        this.api = api;
        this.helper = new ModuleHelper(api);
        
        // Subscribe to enrichment requests
        api.subscribeToEvent("enrichment.request.*", this::handleEnrichmentRequest);
        
        // Start Wazuh polling
        startWazuhIntegration();
        
        helper.log(getName(), "INFO", "Wazuh connector initialized successfully");
    }
    
    @Override
    public void shutdown() {
        helper.log(getName(), "INFO", "Shutting down Wazuh connector");
        // Cleanup resources
    }
}
```

### Step 2: Implement Tool Integration Logic

```java
private void startWazuhIntegration() {
    // Schedule periodic polling of Wazuh API
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    scheduler.scheduleAtFixedRate(this::pollWazuhAlerts, 0, 30, TimeUnit.SECONDS);
}

private void pollWazuhAlerts() {
    try {
        // Call Wazuh REST API
        Request request = new Request.Builder()
            .url("https://wazuh-manager:55000/alerts?level=7,8,9,10")
            .addHeader("Authorization", "Bearer " + getWazuhToken())
            .build();
            
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                WazuhAlertsResponse alertsResponse = gson.fromJson(
                    response.body().string(), WazuhAlertsResponse.class);
                processWazuhAlerts(alertsResponse);
            }
        }
    } catch (Exception e) {
        helper.log(getName(), "ERROR", "Failed to poll Wazuh: " + e.getMessage());
    }
}
```

### Step 3: Transform and Publish Events

```java
private void processWazuhAlerts(WazuhAlertsResponse response) {
    for (WazuhAlert alert : response.getData().getAffectedItems()) {
        // Transform Wazuh alert to standardized format
        WazuhAlertPayload payload = new WazuhAlertPayload();
        payload.setHostId("host-" + alert.getAgent().getIp());
        payload.setAlertType(determineAlertType(alert.getRule()));
        payload.setSignatureId(alert.getRule().getId());
        payload.setSignature(alert.getRule().getDescription());
        payload.setSeverity(mapSeverity(alert.getRule().getLevel()));
        payload.setSourceIp(alert.getAgent().getIp());
        payload.setThreatScore(calculateThreatScore(alert));
        
        // Publish event to framework
        Event<WazuhAlertPayload> event = Event.of("alerts.host.wazuh", payload);
        api.publishEvent(event);
        
        helper.log(getName(), "INFO", 
            "Published Wazuh alert: " + alert.getRule().getDescription());
    }
}
```

### Step 4: Define Custom Payload Classes

```java
public class WazuhAlertPayload {
    private String hostId;
    private String alertType;
    private String signatureId;
    private String signature;
    private String severity;
    private String process;
    private String filePath;
    private String matchedRule;
    private String sourceIp;
    private String destinationIp;
    private String protocol;
    private Integer threatScore;
    
    // Constructors, getters, and setters
    public WazuhAlertPayload() {}
    
    public String getHostId() { return hostId; }
    public void setHostId(String hostId) { this.hostId = hostId; }
    
    public String getAlertType() { return alertType; }
    public void setAlertType(String alertType) { this.alertType = alertType; }
    
    // ... additional getters and setters
}
```

---

## Wazuh Integration Example

### Complete User-Defined Module Implementation Example

Here's a comprehensive example of the `WazuhModule.java` located in `nis1-thesis-udm/src/main/java/com/nis1/thesis/udm/WazuhModule.java`:

```java
package com.nis1.thesis.udm;

import com.nis1.thesis.sdk.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Base64;

/**
 * WazuhModule - User-Defined Module for Wazuh SIEM Integration
 * 
 * This user-defined module demonstrates complete integration with Wazuh SIEM using the SOAR SDK.
 * Located in: nis1-thesis-udm/src/main/java/com/nis1/thesis/udm/WazuhModule.java
 * 
 * Features:
 * - Real-time Wazuh alert polling via REST API
 * - JWT authentication with automatic token refresh
 * - Alert transformation to standardized JSON format
 * - Event publishing through the SDK framework
 * - IP enrichment and threat analysis capabilities
 * - Proper resource management and error handling
 */
public class WazuhModule implements PluggableModule {
    
    // Configuration
    private static final String WAZUH_API_URL = "https://wazuh-manager:55000";
    private static final String USERNAME = "wazuh-user";
    private static final String PASSWORD = "secure-password";
    private static final int POLL_INTERVAL_SECONDS = 30;
    
    // SDK Components
    private CoreSystemApi api;
    private ModuleHelper helper;
    
    // HTTP Client for Wazuh API
    private final OkHttpClient httpClient = new OkHttpClient();
    private final Gson gson = new Gson();
    private String authToken;
    
    // Background Services
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    
    @Override
    public String getName() {
        return "Wazuh User-Defined Module";
    }
    
    @Override
    public void initialize(CoreSystemApi api) {
        this.api = api;
        this.helper = new ModuleHelper(api);
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.running = true;
        
        helper.log(getName(), "INFO", "Initializing Wazuh User-Defined Module");
        
        // Subscribe to framework events
        api.subscribeToEvent("enrichment.request.ip", this::handleIpEnrichment);
        api.subscribeToEvent("mitigation.request.*", this::handleMitigationRequest);
        
        // Start background services
        startAuthentication();
        startAlertPolling();
        
        helper.log(getName(), "INFO", "Wazuh user-defined module initialized successfully");
    }
    
    /**
     * Authenticate with Wazuh API and maintain valid token
     */
    private void startAuthentication() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                authenticateWithWazuh();
            } catch (Exception e) {
                helper.log(getName(), "ERROR", "Authentication failed: " + e.getMessage());
            }
        }, 0, 15, TimeUnit.MINUTES);
    }
    
    private void authenticateWithWazuh() throws IOException {
        String credentials = Base64.getEncoder()
            .encodeToString((USERNAME + ":" + PASSWORD).getBytes());
            
        Request request = new Request.Builder()
            .url(WAZUH_API_URL + "/security/user/authenticate")
            .addHeader("Authorization", "Basic " + credentials)
            .build();
            
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                authToken = json.getAsJsonObject("data").get("token").getAsString();
                helper.log(getName(), "INFO", "Successfully authenticated with Wazuh");
            }
        }
    }
    
    /**
     * Start polling Wazuh for security alerts
     */
    private void startAlertPolling() {
        scheduler.scheduleAtFixedRate(() -> {
            if (authToken != null && running) {
                try {
                    pollWazuhAlerts();
                } catch (Exception e) {
                    helper.log(getName(), "ERROR", "Alert polling failed: " + e.getMessage());
                }
            }
        }, 30, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
    
    private void pollWazuhAlerts() throws IOException {
        Request request = new Request.Builder()
            .url(WAZUH_API_URL + "/alerts?level=7,8,9,10,11,12&limit=50&sort=-timestamp")
            .addHeader("Authorization", "Bearer " + authToken)
            .build();
            
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                processWazuhResponse(response.body().string());
            }
        }
    }
    
    private void processWazuhResponse(String jsonResponse) {
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        
        if (response.has("data") && 
            response.getAsJsonObject("data").has("affected_items")) {
            
            response.getAsJsonObject("data")
                   .getAsJsonArray("affected_items")
                   .forEach(alertElement -> {
                       JsonObject alert = alertElement.getAsJsonObject();
                       publishWazuhAlert(alert);
                   });
        }
    }
    
    /**
     * Convert Wazuh alert to standardized SOAR event
     */
    private void publishWazuhAlert(JsonObject wazuhAlert) {
        try {
            WazuhAlertPayload payload = new WazuhAlertPayload();
            
            // Extract core information
            if (wazuhAlert.has("agent")) {
                JsonObject agent = wazuhAlert.getAsJsonObject("agent");
                payload.setHostId("host-" + agent.get("ip").getAsString());
                payload.setSourceIp(agent.get("ip").getAsString());
            }
            
            if (wazuhAlert.has("rule")) {
                JsonObject rule = wazuhAlert.getAsJsonObject("rule");
                payload.setSignatureId(rule.get("id").getAsString());
                payload.setSignature(rule.get("description").getAsString());
                payload.setSeverity(mapWazuhSeverity(rule.get("level").getAsInt()));
                payload.setAlertType(determineAlertType(rule));
                payload.setThreatScore(calculateThreatScore(rule.get("level").getAsInt()));
            }
            
            // Extract additional context
            if (wazuhAlert.has("data")) {
                JsonObject data = wazuhAlert.getAsJsonObject("data");
                if (data.has("srcip")) payload.setSourceIp(data.get("srcip").getAsString());
                if (data.has("dstip")) payload.setDestinationIp(data.get("dstip").getAsString());
                if (data.has("protocol")) payload.setProtocol(data.get("protocol").getAsString());
            }
            
            // Publish standardized event
            Event<WazuhAlertPayload> event = Event.of("alerts.host.wazuh", payload);
            api.publishEvent(event);
            
            helper.log(getName(), "INFO", 
                String.format("Published alert: %s (Severity: %s, Score: %d)",
                    payload.getSignature(), payload.getSeverity(), payload.getThreatScore()));
                    
        } catch (Exception e) {
            helper.log(getName(), "ERROR", "Failed to process Wazuh alert: " + e.getMessage());
        }
    }
    
    /**
     * Handle IP enrichment requests from other modules
     */
    private void handleIpEnrichment(Event<?> event) {
        try {
            EnrichmentRequestData request = (EnrichmentRequestData) event.getData();
            String ipAddress = request.getIpAddress();
            
            helper.log(getName(), "INFO", "Processing IP enrichment for: " + ipAddress);
            
            // Query Wazuh for historical data about this IP
            enrichIpWithWazuhData(ipAddress);
            
        } catch (Exception e) {
            helper.log(getName(), "ERROR", "IP enrichment failed: " + e.getMessage());
        }
    }
    
    private void enrichIpWithWazuhData(String ipAddress) throws IOException {
        Request request = new Request.Builder()
            .url(WAZUH_API_URL + "/alerts?q=data.srcip=" + ipAddress + "&limit=100")
            .addHeader("Authorization", "Bearer " + authToken)
            .build();
            
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                JsonObject data = gson.fromJson(response.body().string(), JsonObject.class);
                
                // Analyze historical alerts for this IP
                IpReputationData reputation = analyzeIpReputation(ipAddress, data);
                
                // Publish enrichment result
                Event<IpReputationData> enrichmentEvent = 
                    Event.of("enrichment.result.ip", reputation);
                api.publishEvent(enrichmentEvent);
                
                helper.log(getName(), "INFO", 
                    String.format("Published IP reputation for %s: Malicious=%s", 
                        ipAddress, reputation.isMalicious()));
            }
        }
    }
    
    private IpReputationData analyzeIpReputation(String ipAddress, JsonObject alertData) {
        // Analyze Wazuh historical data to determine IP reputation
        int alertCount = 0;
        int highSeverityAlerts = 0;
        
        if (alertData.has("data") && 
            alertData.getAsJsonObject("data").has("affected_items")) {
            
            alertCount = alertData.getAsJsonObject("data")
                                 .getAsJsonArray("affected_items").size();
                                 
            // Count high-severity alerts
            alertData.getAsJsonObject("data")
                    .getAsJsonArray("affected_items")
                    .forEach(alert -> {
                        JsonObject rule = alert.getAsJsonObject().getAsJsonObject("rule");
                        if (rule.get("level").getAsInt() >= 7) {
                            // Increment high severity counter (would need proper counter)
                        }
                    });
        }
        
        boolean isMalicious = alertCount > 5 || highSeverityAlerts > 2;
        int confidence = Math.min(100, alertCount * 10 + highSeverityAlerts * 25);
        
        return new IpReputationData(ipAddress, isMalicious, "Wazuh-Historical", 
                                  "behavioral_analysis", confidence);
    }
    
    /**
     * Handle mitigation requests
     */
    private void handleMitigationRequest(Event<?> event) {
        try {
            MitigationCommandData command = (MitigationCommandData) event.getData();
            
            helper.log(getName(), "INFO", 
                String.format("Processing mitigation request: %s for host %s",
                    command.getAction(), command.getTargetHost()));
            
            // In a real implementation, this would execute the mitigation
            // through Wazuh's active response capabilities
            executeMitigationAction(command);
            
        } catch (Exception e) {
            helper.log(getName(), "ERROR", "Mitigation execution failed: " + e.getMessage());
        }
    }
    
    private void executeMitigationAction(MitigationCommandData command) {
        // Example: Execute Wazuh active response
        helper.log(getName(), "INFO", 
            String.format("Executed %s action on %s via Wazuh active response",
                command.getAction(), command.getTargetHost()));
    }
    
    // Utility Methods
    private String mapWazuhSeverity(int level) {
        if (level >= 12) return "critical";
        if (level >= 7) return "high";
        if (level >= 4) return "medium";
        return "low";
    }
    
    private String determineAlertType(JsonObject rule) {
        String description = rule.get("description").getAsString().toLowerCase();
        
        if (description.contains("ransomware") || description.contains("encryption")) {
            return "ransomware_detection";
        } else if (description.contains("malware") || description.contains("virus")) {
            return "malware_detection";
        } else if (description.contains("brute") || description.contains("authentication")) {
            return "authentication_attack";
        } else if (description.contains("rootkit") || description.contains("privilege")) {
            return "privilege_escalation";
        }
        
        return "security_violation";
    }
    
    private Integer calculateThreatScore(int wazuhLevel) {
        // Convert Wazuh levels (0-15) to threat score (0-100)
        return Math.min(100, (int) ((wazuhLevel / 15.0) * 100));
    }
    
    @Override
    public void shutdown() {
        running = false;
        helper.log(getName(), "INFO", "Shutting down Wazuh User-Defined Module");
        
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        helper.log(getName(), "INFO", "Wazuh user-defined module shutdown complete");
    }
}
```

---

## Event Types and Routing

### Standard Event Types

| Category | Event Type | Description | Example Source |
|----------|------------|-------------|----------------|
| **Host Alerts** | `alerts.host.wazuh` | Host-based security events | Wazuh HIDS |
| | `alerts.host.crowdstrike` | Endpoint detection events | CrowdStrike Falcon |
| | `alerts.host.carbon_black` | Behavioral analysis alerts | VMware Carbon Black |
| **Network Alerts** | `alerts.network.suricata` | Network intrusion detection | Suricata IDS |
| | `alerts.network.zeek` | Network protocol analysis | Zeek NSM |
| | `alerts.network.firewall` | Firewall security events | pfSense, FortiGate |
| **Threat Intelligence** | `enrichment.result.ip` | IP reputation data | VirusTotal, AlienVault |
| | `enrichment.result.domain` | Domain reputation | OpenDNS, Quad9 |
| | `enrichment.result.hash` | File hash analysis | VirusTotal, Hybrid Analysis |
| **Mitigation Actions** | `mitigation.network.block` | Network-level blocking | SDN Controllers |
| | `mitigation.host.quarantine` | Host isolation | Network Access Control |
| | `mitigation.user.disable` | User account actions | Active Directory |

### Routing Patterns

The framework supports AMQP topic exchange patterns for flexible message routing:

- `alerts.*` - Subscribe to all alert types
- `alerts.host.*` - Subscribe to all host-based alerts
- `*.wazuh` - Subscribe to all events from Wazuh modules
- `mitigation.network.*` - Subscribe to network-level mitigation actions

---

## Implementation Patterns

### 1. Reactive Pattern - Event-Driven Processing

```java
@Override
public void initialize(CoreSystemApi api) {
    // Subscribe to multiple event types
    api.subscribeToEvent("alerts.host.*", this::handleHostAlert);
    api.subscribeToEvent("enrichment.request.*", this::handleEnrichmentRequest);
    api.subscribeToEvent("mitigation.result.*", this::handleMitigationResult);
}

private void handleHostAlert(Event<?> event) {
    HostAlertData alert = (HostAlertData) event.getData();
    
    // Process alert and potentially trigger additional actions
    if ("critical".equals(alert.getSeverity())) {
        requestIpEnrichment(alert.getSourceIp());
    }
}
```

### 2. Proactive Pattern - Scheduled Operations

```java
private void startProactiveScanning() {
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Perform threat hunting every 15 minutes
    scheduler.scheduleAtFixedRate(this::performThreatHunt, 0, 15, TimeUnit.MINUTES);
    
    // Generate security posture reports every hour
    scheduler.scheduleAtFixedRate(this::generatePostureReport, 0, 60, TimeUnit.MINUTES);
}
```

### 3. Correlation Pattern - Multi-Source Analysis

```java
private final Map<String, List<Event<?>>> correlationBuffer = new ConcurrentHashMap<>();

private void handleAlert(Event<?> event) {
    String sourceIp = extractSourceIp(event);
    
    // Buffer events for correlation
    correlationBuffer.computeIfAbsent(sourceIp, k -> new ArrayList<>()).add(event);
    
    // Analyze correlation after collecting multiple events
    analyzeCorrelation(sourceIp);
}

private void analyzeCorrelation(String sourceIp) {
    List<Event<?>> events = correlationBuffer.get(sourceIp);
    
    if (events.size() >= 3) { // Correlation threshold
        CorrelationResult result = performCorrelationAnalysis(events);
        
        if (result.isHighRisk()) {
            publishCorrelatedThreatEvent(sourceIp, result);
        }
    }
}
```

---

## Security Considerations

### 1. Authentication and Authorization

```java
// Use secure authentication for external API calls
private void authenticateSecurely() {
    // Use environment variables for credentials
    String username = System.getenv("WAZUH_USERNAME");
    String password = System.getenv("WAZUH_PASSWORD");
    
    // Implement token refresh logic
    if (isTokenExpired()) {
        refreshAuthToken();
    }
}
```

### 2. Data Sanitization

```java
private void publishSanitizedEvent(WazuhAlert rawAlert) {
    WazuhAlertPayload payload = new WazuhAlertPayload();
    
    // Sanitize sensitive data
    payload.setSourceIp(sanitizeIpAddress(rawAlert.getSourceIp()));
    payload.setProcess(sanitizeFilePath(rawAlert.getProcess()));
    
    Event<WazuhAlertPayload> event = Event.of("alerts.host.wazuh", payload);
    api.publishEvent(event);
}

private String sanitizeFilePath(String path) {
    // Remove or mask sensitive path information
    return path.replaceAll("\\\\Users\\\\[^\\\\]+", "\\\\Users\\\\[USER]");
}
```

### 3. Error Handling and Resilience

```java
private void robustEventPublishing(Event<?> event) {
    int maxRetries = 3;
    int retryCount = 0;
    
    while (retryCount < maxRetries) {
        try {
            api.publishEvent(event);
            return; // Success
        } catch (Exception e) {
            retryCount++;
            helper.log(getName(), "WARN", 
                String.format("Event publishing failed, attempt %d/%d: %s", 
                    retryCount, maxRetries, e.getMessage()));
            
            if (retryCount < maxRetries) {
                try {
                    Thread.sleep(1000 * retryCount); // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    // Log failure after all retries
    helper.log(getName(), "ERROR", "Failed to publish event after " + maxRetries + " attempts");
}
```

---

## Troubleshooting Guide

### Common Issues and Solutions

#### 1. Module Not Loading
**Symptoms:** Module doesn't appear in logs, events not processed  
**Solutions:**
- Verify PluggableModule implementation
- Check for exceptions in initialize() method
- Ensure proper package structure and JAR manifest

#### 2. Events Not Being Received
**Symptoms:** subscribeToEvent() not triggering callbacks  
**Solutions:**
- Verify event type patterns match published events
- Check RabbitMQ connection status
- Ensure proper event deserialization

#### 3. Authentication Failures
**Symptoms:** HTTP 401/403 errors from external APIs  
**Solutions:**
- Verify credentials and token refresh logic
- Check network connectivity to external services
- Implement proper error handling and retry logic

#### 4. Memory Leaks
**Symptoms:** Increasing memory usage over time  
**Solutions:**
- Properly close HTTP connections and streams
- Clear correlation buffers periodically
- Implement proper shutdown() cleanup

### Debug Configuration

```java
// Enable detailed logging
helper.log(getName(), "DEBUG", "Processing event: " + event.getType());
helper.log(getName(), "DEBUG", "Event payload: " + gson.toJson(event.getData()));

// Monitor performance
long startTime = System.currentTimeMillis();
processEvent(event);
long duration = System.currentTimeMillis() - startTime;
helper.log(getName(), "PERF", "Event processing took " + duration + "ms");
```

---

## Conclusion

This SDK provides a robust foundation for building security middleware components that integrate seamlessly with the SOAR framework. By following the patterns and examples in this document, developers can create reliable, scalable security integrations that enhance overall security posture through automated orchestration and response capabilities.

### Next Steps
1. Review existing security tools in your environment
2. Create the `nis1-thesis-udm` directory structure in your project
3. Identify integration opportunities using the patterns above
4. Implement user-defined modules following the `*Module.java` naming convention
5. Place modules in `nis1-thesis-udm/src/main/java/com/nis1/thesis/udm/`
6. Deploy and monitor in development environment
7. Scale to production with proper monitoring and alerting

### User-Defined Module Checklist
Before deploying your custom security integrations, ensure:

**Directory Structure**: Module is located in `nis1-thesis-udm/src/main/java/com/nis1/thesis/udm/`  
**Naming Convention**: File follows `*Module.java` pattern  
**Package Declaration**: Uses `package com.nis1.thesis.udm;`  
**Interface Implementation**: Implements `PluggableModule` interface  
**Standardized Messages**: Uses the standardized JSON message format  
**Resource Management**: Proper initialization and shutdown lifecycle  

For additional support and examples, refer to the complete `WazuhModule.java` implementation in the `nis1-thesis-udm` directory and the SDK documentation.
