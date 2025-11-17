package com.nis1.thesis.sdk;

/**
 * Data payload for IP reputation events from threat intelligence sources.
 * <p>
 * This class encapsulates threat intelligence information about IP addresses,
 * including reputation status, confidence levels, and categorization data.
 * It is typically used by threat intelligence modules to share IP reputation
 * data with other security modules in the SOAR framework.
 * </p>
 * <p>
 * The reputation data can come from various threat intelligence feeds such as
 * commercial providers, open source feeds, or internal threat intelligence systems.
 * </p>
 *
 * @author NIS1
 * @version 1.0
 * @since September 10, 2025
 */
public class IpReputationData {
    private String ipAddress;
    private boolean isMalicious;
    private String source;
    private String category;
    private Integer confidenceScore;

    /**
     * Default constructor for JSON deserialization.
     */
    public IpReputationData() {}

    /**
     * Constructs IP reputation data with basic parameters.
     * 
     * @param ipAddress The IP address being reported on
     * @param isMalicious True if the IP is identified as malicious, false otherwise
     * @param source The name of the threat intelligence source
     */
    public IpReputationData(String ipAddress, boolean isMalicious, String source) {
        this.ipAddress = ipAddress;
        this.isMalicious = isMalicious;
        this.source = source;
    }

    /**
     * Constructs IP reputation data with complete parameters including categorization and confidence.
     * 
     * @param ipAddress The IP address being reported on
     * @param isMalicious True if the IP is identified as malicious, false otherwise
     * @param source The name of the threat intelligence source
     * @param category The threat category (e.g., "malware", "phishing", "botnet")
     * @param confidenceScore Confidence level from 0-100, where higher values indicate greater certainty
     */
    public IpReputationData(String ipAddress, boolean isMalicious, String source, String category, Integer confidenceScore) {
        this.ipAddress = ipAddress;
        this.isMalicious = isMalicious;
        this.source = source;
        this.category = category;
        this.confidenceScore = confidenceScore;
    }

    /**
     * Returns the IP address being reported on.
     * 
     * @return The IP address
     */
    public String getIpAddress() { return ipAddress; }
    
    /**
     * Sets the IP address being reported on.
     * 
     * @param ipAddress The IP address
     */
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    /**
     * Returns whether the IP address is identified as malicious.
     * 
     * @return True if malicious, false otherwise
     */
    public boolean isMalicious() { return isMalicious; }
    
    /**
     * Sets whether the IP address is identified as malicious.
     * 
     * @param malicious True if malicious, false otherwise
     */
    public void setMalicious(boolean malicious) { isMalicious = malicious; }

    /**
     * Returns the name of the threat intelligence source.
     * 
     * @return The source name
     */
    public String getSource() { return source; }
    
    /**
     * Sets the name of the threat intelligence source.
     * 
     * @param source The source name
     */
    public void setSource(String source) { this.source = source; }

    /**
     * Returns the threat category classification.
     * 
     * @return The threat category (e.g., "malware", "phishing", "botnet")
     */
    public String getCategory() { return category; }
    
    /**
     * Sets the threat category classification.
     * 
     * @param category The threat category (e.g., "malware", "phishing", "botnet")
     */
    public void setCategory(String category) { this.category = category; }

    /**
     * Returns the confidence score for this reputation data.
     * 
     * @return Confidence level from 0-100, where higher values indicate greater certainty
     */
    public Integer getConfidenceScore() { return confidenceScore; }
    
    /**
     * Sets the confidence score for this reputation data.
     * 
     * @param confidenceScore Confidence level from 0-100, where higher values indicate greater certainty
     */
    public void setConfidenceScore(Integer confidenceScore) { this.confidenceScore = confidenceScore; }
}