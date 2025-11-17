package com.nis1.thesis.sdk;

/**
 * Data payload for enrichment request events sent to threat intelligence modules.
 * <p>
 * This class represents requests for threat intelligence enrichment on various
 * indicators of compromise (IoCs) such as IP addresses, domains, or file hashes.
 * Enrichment modules use this data to query external threat intelligence sources
 * and provide additional context about potential threats.
 * </p>
 * <p>
 * Enrichment requests can specify different types of intelligence gathering
 * operations and include correlation identifiers for tracking and response coordination.
 * </p>
 *
 * @author NIS1
 * @version 1.0
 * @since September 10, 2025
 */
public class EnrichmentRequestData {
    private String ipAddress;
    private String domain;
    private String fileHash;
    private String enrichmentType;
    private String requestId;

    /**
     * Default constructor for JSON deserialization.
     */
    public EnrichmentRequestData() {}

    /**
     * Constructs an IP reputation enrichment request.
     * 
     * @param ipAddress The IP address to request enrichment data for
     */
    public EnrichmentRequestData(String ipAddress) {
        this.ipAddress = ipAddress;
        this.enrichmentType = "IP_REPUTATION";
    }

    /**
     * Constructs an enrichment request with specified type and correlation ID.
     * 
     * @param enrichmentType The type of enrichment requested (e.g., "IP_REPUTATION", "DOMAIN_LOOKUP", "FILE_HASH")
     * @param requestId Correlation identifier for tracking this enrichment request
     */
    public EnrichmentRequestData(String enrichmentType, String requestId) {
        this.enrichmentType = enrichmentType;
        this.requestId = requestId;
    }

    /**
     * Returns the IP address to be enriched with threat intelligence.
     * 
     * @return The IP address, or null if this request is not for IP enrichment
     */
    public String getIpAddress() { return ipAddress; }
    
    /**
     * Sets the IP address to be enriched with threat intelligence.
     * 
     * @param ipAddress The IP address to enrich
     */
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    /**
     * Returns the domain name to be enriched with threat intelligence.
     * 
     * @return The domain name, or null if this request is not for domain enrichment
     */
    public String getDomain() { return domain; }
    
    /**
     * Sets the domain name to be enriched with threat intelligence.
     * 
     * @param domain The domain name to enrich
     */
    public void setDomain(String domain) { this.domain = domain; }

    /**
     * Returns the file hash to be enriched with threat intelligence.
     * 
     * @return The file hash (MD5, SHA1, SHA256), or null if this request is not for file enrichment
     */
    public String getFileHash() { return fileHash; }
    
    /**
     * Sets the file hash to be enriched with threat intelligence.
     * 
     * @param fileHash The file hash (MD5, SHA1, SHA256) to enrich
     */
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    /**
     * Returns the type of enrichment being requested.
     * 
     * @return The enrichment type (e.g., "IP_REPUTATION", "DOMAIN_LOOKUP", "FILE_HASH")
     */
    public String getEnrichmentType() { return enrichmentType; }
    
    /**
     * Sets the type of enrichment being requested.
     * 
     * @param enrichmentType The enrichment type (e.g., "IP_REPUTATION", "DOMAIN_LOOKUP", "FILE_HASH")
     */
    public void setEnrichmentType(String enrichmentType) { this.enrichmentType = enrichmentType; }

    /**
     * Returns the correlation identifier for tracking this enrichment request.
     * 
     * @return The request ID for correlation and tracking
     */
    public String getRequestId() { return requestId; }
    
    /**
     * Sets the correlation identifier for tracking this enrichment request.
     * 
     * @param requestId The request ID for correlation and tracking
     */
    public void setRequestId(String requestId) { this.requestId = requestId; }
}