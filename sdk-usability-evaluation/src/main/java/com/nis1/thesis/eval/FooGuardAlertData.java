package com.nis1.thesis.eval;

import com.google.gson.annotations.SerializedName;

/**
 * FooGuardAlertData - Payload for FooGuard (fictional IDS) alerts.
 *
 * This class is provided complete - it is not part of the fill-in-the-blank exercise. It exists
 * so you can see one worked example of Domain Correspondence / Role Expressiveness done the way
 * every real module in this repo (SuricataAlertData, MaltrailAlertData, ...) does it: field names
 * mirror the shared vocabulary WorkflowMatcher already understands (severity, alert_type,
 * category, threat_score, source_ip, ...) rather than inventing new ones, so this alert type would
 * plug into existing workflow YAML unmodified.
 *
 * Worth reading before you start FooGuardStandaloneSkeleton.java - you'll be populating this class
 * from FooGuard's own field names (src, dst, sev, rule, ...), and the mapping choices already made
 * here are the ones a real module author would have to make themselves.
 */
public class FooGuardAlertData {

    @SerializedName("alert_id")
    private String alertId;
    @SerializedName("signature")
    private String signature; // FooGuard's "rule" field, e.g. "ssh-brute-force"

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

    @SerializedName("severity")
    private String severity; // critical, high, medium, low - normalized, lowercase, exact match
    @SerializedName("category")
    private String category;
    @SerializedName("alert_type")
    private String alertType; // same as category - workflow YAML matches on this one, not category

    @SerializedName("threat_score")
    private Integer threatScore; // 0-100
    @SerializedName("confidence_score")
    private Integer confidenceScore; // 0-100

    // FooGuard-specific field (no equivalent in the common block above)
    @SerializedName("foo_priority")
    private String fooPriority; // FooGuard's own raw priority string (P1-P4), kept for traceability

    public FooGuardAlertData() {
    }

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

    public String getFooPriority() {
        return fooPriority;
    }

    public void setFooPriority(String fooPriority) {
        this.fooPriority = fooPriority;
    }

    @Override
    public String toString() {
        return "FooGuardAlertData{" +
                "alertId='" + alertId + '\'' +
                ", signature='" + signature + '\'' +
                ", severity='" + severity + '\'' +
                ", sourceIp='" + sourceIp + '\'' +
                ", threatScore=" + threatScore +
                '}';
    }
}
