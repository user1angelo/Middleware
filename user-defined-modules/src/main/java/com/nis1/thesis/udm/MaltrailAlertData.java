package com.nis1.thesis.udm;

import com.google.gson.annotations.SerializedName;

/**
 * MaltrailAlertData - Payload for Maltrail threat-intelligence alerts
 *
 * Mirrors SuricataAlertData's field names/types wherever semantically equivalent
 * so WorkflowMatcher's existing condition evaluation (severity, alert_type/category,
 * signature, threat_score) works unmodified against Maltrail-sourced alerts. Adds a
 * few Maltrail-specific metadata fields (trail, reference, sensor) that Maltrail
 * provides but Suricata does not.
 *
 * Used by MaltrailModule to publish standardized alerts.network.maltrail events
 * to the SOAR framework via the ModuleRegistry.
 */
public class MaltrailAlertData {

    // Alert identification
    @SerializedName("alert_id")
    private String alertId;
    @SerializedName("signature")
    private String signature; // Maltrail's "info" field (e.g. "ransomware", "malware feed: X")

    // Network information
    @SerializedName("source_ip")
    private String sourceIp;
    @SerializedName("destination_ip")
    private String destinationIp;
    @SerializedName("source_port")
    private Integer sourcePort;
    @SerializedName("destination_port")
    private Integer destinationPort;
    @SerializedName("protocol")
    private String protocol;

    // Classification
    @SerializedName("severity")
    private String severity; // critical, high, medium, low - normalized to match WorkflowMatcher's scale
    @SerializedName("category")
    private String category; // e.g. ransomware, maltrail_ip, maltrail_dns, maltrail_url
    @SerializedName("alert_type")
    private String alertType; // Same as category for consistency

    // Threat scoring
    @SerializedName("threat_score")
    private Integer threatScore; // 0-100
    @SerializedName("confidence_score")
    private Integer confidenceScore; // 0-100

    // Maltrail-specific metadata (no Suricata equivalent)
    @SerializedName("trail")
    private String trail; // the matched indicator (e.g. a C2 domain/IP)
    @SerializedName("reference")
    private String reference; // source feed name, or "(static)"
    @SerializedName("sensor")
    private String sensor; // Maltrail sensor hostname

    /**
     * Default constructor for JSON deserialization
     */
    public MaltrailAlertData() {
    }

    /**
     * Constructor with essential fields
     */
    public MaltrailAlertData(String alertId, String signature, String sourceIp,
            String destinationIp, String severity, String trail) {
        this.alertId = alertId;
        this.signature = signature;
        this.sourceIp = sourceIp;
        this.destinationIp = destinationIp;
        this.severity = severity;
        this.trail = trail;
    }

    // Getters and Setters

    public String getAlertId() {
        return alertId;
    }

    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
    }

    public String getDestinationIp() {
        return destinationIp;
    }

    public void setDestinationIp(String destinationIp) {
        this.destinationIp = destinationIp;
    }

    public Integer getSourcePort() {
        return sourcePort;
    }

    public void setSourcePort(Integer sourcePort) {
        this.sourcePort = sourcePort;
    }

    public Integer getDestinationPort() {
        return destinationPort;
    }

    public void setDestinationPort(Integer destinationPort) {
        this.destinationPort = destinationPort;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAlertType() {
        return alertType;
    }

    public void setAlertType(String alertType) {
        this.alertType = alertType;
    }

    public Integer getThreatScore() {
        return threatScore;
    }

    public void setThreatScore(Integer threatScore) {
        this.threatScore = threatScore;
    }

    public Integer getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Integer confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getTrail() {
        return trail;
    }

    public void setTrail(String trail) {
        this.trail = trail;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getSensor() {
        return sensor;
    }

    public void setSensor(String sensor) {
        this.sensor = sensor;
    }

    @Override
    public String toString() {
        return "MaltrailAlertData{" +
                "alertId='" + alertId + '\'' +
                ", signature='" + signature + '\'' +
                ", severity='" + severity + '\'' +
                ", sourceIp='" + sourceIp + '\'' +
                ", destinationIp='" + destinationIp + '\'' +
                ", trail='" + trail + '\'' +
                ", threatScore=" + threatScore +
                '}';
    }
}
