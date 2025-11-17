package com.nis1.thesis.sdk;

/**
 * Utility class that simplifies common tasks for SOAR module developers.
 * <p>
 * This helper class provides convenience methods for publishing standard event types
 * and handling common operations, reducing boilerplate code in module implementations.
 * It wraps the CoreSystemApi to provide type-safe, simplified methods for common
 * security event publishing scenarios.
 * </p>
 * <p>
 * Module developers should use this helper class to ensure consistent event formatting
 * and to reduce the complexity of event publishing within their modules.
 * </p>
 *
 * @author NIS1
 * @version 1.0
 * @since September 10, 2025
 */
public class ModuleHelper {
    private final CoreSystemApi api;

    /**
     * Constructs a new ModuleHelper with the specified CoreSystemApi.
     * 
     * @param api The core system API instance to use for event publishing
     * @throws NullPointerException if api is null
     */
    public ModuleHelper(CoreSystemApi api) {
        this.api = api;
    }

    /**
     * Publishes an IP reputation event with simplified parameters.
     * <p>
     * This convenience method creates and publishes an IP reputation event with the
     * specified parameters, automatically generating appropriate event metadata.
     * </p>
     * 
     * @param ipAddress The IP address being reported on
     * @param isMalicious True if the IP is identified as malicious, false otherwise
     * @param source The name of the threat intelligence source (e.g., "VirusTotal", "AlienVault")
     */
    public void publishIpReputation(String ipAddress, boolean isMalicious, String source) {
        IpReputationData payload = new IpReputationData(ipAddress, isMalicious, source);
        Event<IpReputationData> event = Event.of("IP_REPUTATION_" + source.toUpperCase(), payload);
        api.publishEvent(event);
    }

    /**
     * Publishes a host alert event with simplified parameters.
     * <p>
     * This convenience method creates and publishes a host-based security alert
     * with the specified parameters, using standard event formatting.
     * </p>
     * 
     * @param sourceIp The IP address of the host generating the alert
     * @param description A human-readable description of the security event
     * @param severity The severity level (e.g., "LOW", "MEDIUM", "HIGH", "CRITICAL")
     */
    public void publishHostAlert(String sourceIp, String description, String severity) {
        HostAlertData payload = new HostAlertData(sourceIp, description, severity);
        Event<HostAlertData> event = Event.of("HOST_ALERT", payload);
        api.publishEvent(event);
    }

    /**
     * Publishes a Network Intrusion Detection System (NIDS) alert event with simplified parameters.
     * <p>
     * This convenience method creates and publishes a NIDS alert for network-based
     * security incidents, including source/destination information and signature details.
     * </p>
     * 
     * @param sourceIp The source IP address of the network traffic
     * @param destinationIp The destination IP address of the network traffic
     * @param signature The detection signature or rule that triggered the alert
     * @param severity The severity level of the alert (e.g., "1", "2", "3" where 1 is highest)
     */
    public void publishNidsAlert(String sourceIp, String destinationIp, String signature, String severity) {
        NidsAlertData payload = new NidsAlertData(sourceIp, destinationIp, signature, severity);
        Event<NidsAlertData> event = Event.of("NIDS_ALERT", payload);
        api.publishEvent(event);
    }

    /**
     * Publishes a mitigation command event with simplified parameters.
     * <p>
     * This convenience method creates and publishes a command to initiate automated
     * security response actions against identified threats.
     * </p>
     * 
     * @param targetHost The hostname or IP address of the target for mitigation
     * @param action The specific mitigation action to perform
     * @param justification Human-readable reasoning for why this action was chosen
     */
    public void publishMitigationCommand(String targetHost, MitigationAction action, String justification) {
        MitigationCommandData payload = new MitigationCommandData(targetHost, action, justification);
        Event<MitigationCommandData> event = Event.of("INITIATE_MITIGATION", payload);
        api.publishEvent(event);
    }

    /**
     * Publishes an enrichment request for IP reputation analysis.
     * <p>
     * This convenience method creates and publishes a request for threat intelligence
     * enrichment on a specific IP address, typically handled by threat intelligence modules.
     * </p>
     * 
     * @param ipAddress The IP address to request enrichment data for
     */
    public void requestIpEnrichment(String ipAddress) {
        EnrichmentRequestData payload = new EnrichmentRequestData(ipAddress);
        Event<EnrichmentRequestData> event = Event.of("ENRICHMENT_REQUEST_IP", payload);
        api.publishEvent(event);
    }

    /**
     * Simple logging method for module operations with module name prefixing.
     * <p>
     * Provides a standardized logging format for module messages with automatic
     * module name prefixing for easier log analysis and debugging.
     * </p>
     * 
     * @param moduleName The name of the module generating the log message
     * @param message The log message content
     */
    public void log(String moduleName, String message) {
        System.out.println("[" + moduleName + "] " + message);
    }

    /**
     * Logs a message with both module name and severity level prefixes.
     * <p>
     * Provides enhanced logging with severity level indication for better
     * log filtering and analysis. Uses a standardized format for consistency.
     * </p>
     * 
     * @param moduleName The name of the module generating the log message
     * @param level The severity level (e.g., "INFO", "WARN", "ERROR", "DEBUG")
     * @param message The log message content
     */
    public void log(String moduleName, String level, String message) {
        System.out.println("[" + moduleName + "][" + level + "] " + message);
    }
}