package com.nis1.thesis.udm;

import com.google.gson.annotations.SerializedName;

/**
 * Fail2banAlertData - Payload for Fail2ban brute-force/ban alerts
 *
 * Mirrors SuricataAlertData's field names wherever semantically equivalent so
 * WorkflowMatcher's existing condition evaluation works unmodified against
 * Fail2ban-sourced alerts. Adds a couple of Fail2ban-specific fields (jail, banAction)
 * that Suricata has no equivalent for.
 *
 * Used by Fail2banModule to publish standardized alerts.host.fail2ban events to the
 * SOAR framework via the ModuleRegistry.
 */
public class Fail2banAlertData {

    @SerializedName("alert_id")
    private String alertId;
    @SerializedName("signature")
    private String signature; // e.g. "SSH brute-force (jail: sshd)"

    @SerializedName("source_ip")
    private String sourceIp;

    @SerializedName("severity")
    private String severity; // critical, high, medium, low
    @SerializedName("category")
    private String category; // e.g. brute_force
    @SerializedName("alert_type")
    private String alertType; // same as category for consistency

    @SerializedName("threat_score")
    private Integer threatScore; // 0-100
    @SerializedName("confidence_score")
    private Integer confidenceScore; // 0-100

    // Fail2ban-specific metadata (no Suricata equivalent)
    @SerializedName("jail")
    private String jail; // e.g. "sshd", "apache-auth"
    @SerializedName("ban_action")
    private String banAction; // "Ban" or "Unban"

    /**
     * Default constructor for JSON deserialization
     */
    public Fail2banAlertData() {
    }

    /**
     * Constructor with essential fields
     */
    public Fail2banAlertData(String alertId, String signature, String sourceIp,
            String severity, String jail, String banAction) {
        this.alertId = alertId;
        this.signature = signature;
        this.sourceIp = sourceIp;
        this.severity = severity;
        this.jail = jail;
        this.banAction = banAction;
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

    public String getJail() {
        return jail;
    }

    public void setJail(String jail) {
        this.jail = jail;
    }

    public String getBanAction() {
        return banAction;
    }

    public void setBanAction(String banAction) {
        this.banAction = banAction;
    }

    @Override
    public String toString() {
        return "Fail2banAlertData{" +
                "alertId='" + alertId + '\'' +
                ", signature='" + signature + '\'' +
                ", severity='" + severity + '\'' +
                ", sourceIp='" + sourceIp + '\'' +
                ", jail='" + jail + '\'' +
                ", banAction='" + banAction + '\'' +
                ", threatScore=" + threatScore +
                '}';
    }
}
