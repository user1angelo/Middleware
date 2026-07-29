package com.nis1.thesis.udm;

import com.google.gson.annotations.SerializedName;

/**
 * SysmonAlertData - Payload for Windows Sysmon host-telemetry alerts
 *
 * Mirrors SuricataAlertData's field names wherever semantically equivalent so
 * WorkflowMatcher's existing condition evaluation works unmodified against
 * Sysmon-sourced alerts. Adds Sysmon-specific metadata (eventId, computer, image,
 * commandLine) that Suricata has no equivalent for.
 *
 * Used by SysmonModule to publish standardized alerts.host.sysmon events to the
 * SOAR framework via the ModuleRegistry.
 */
public class SysmonAlertData {

    @SerializedName("alert_id")
    private String alertId;
    @SerializedName("signature")
    private String signature; // human-readable description of what matched

    @SerializedName("source_ip")
    private String sourceIp; // host IP if known, otherwise unset

    @SerializedName("severity")
    private String severity; // critical, high, medium, low
    @SerializedName("category")
    private String category; // e.g. ransomware, process_creation
    @SerializedName("alert_type")
    private String alertType; // same as category for consistency

    @SerializedName("threat_score")
    private Integer threatScore; // 0-100
    @SerializedName("confidence_score")
    private Integer confidenceScore; // 0-100

    // Sysmon-specific metadata (no Suricata equivalent)
    @SerializedName("sysmon_event_id")
    private Integer sysmonEventId; // 1 = Process Create, 11 = File Create
    @SerializedName("computer")
    private String computer; // hostname the event originated from
    @SerializedName("image")
    private String image; // full path of the process image
    @SerializedName("command_line")
    private String commandLine;
    @SerializedName("user")
    private String user;

    /**
     * Default constructor for JSON deserialization
     */
    public SysmonAlertData() {
    }

    /**
     * Constructor with essential fields
     */
    public SysmonAlertData(String alertId, String signature, String severity,
            Integer sysmonEventId, String computer, String commandLine) {
        this.alertId = alertId;
        this.signature = signature;
        this.severity = severity;
        this.sysmonEventId = sysmonEventId;
        this.computer = computer;
        this.commandLine = commandLine;
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

    public Integer getSysmonEventId() {
        return sysmonEventId;
    }

    public void setSysmonEventId(Integer sysmonEventId) {
        this.sysmonEventId = sysmonEventId;
    }

    public String getComputer() {
        return computer;
    }

    public void setComputer(String computer) {
        this.computer = computer;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getCommandLine() {
        return commandLine;
    }

    public void setCommandLine(String commandLine) {
        this.commandLine = commandLine;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    @Override
    public String toString() {
        return "SysmonAlertData{" +
                "alertId='" + alertId + '\'' +
                ", signature='" + signature + '\'' +
                ", severity='" + severity + '\'' +
                ", sysmonEventId=" + sysmonEventId +
                ", computer='" + computer + '\'' +
                ", commandLine='" + commandLine + '\'' +
                ", threatScore=" + threatScore +
                '}';
    }
}
