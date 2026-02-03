package com.nis1.thesis.udm;

/**
 * ZeekAlertData - Comprehensive payload for Zeek (Bro) alerts
 *
 * Represents security alerts derived from Zeek logs (specifically notice.log).
 * Standardizes Zeek data for the SOAR framework.
 */
public class ZeekAlertData {

    // Identification
    private String alertId; // generated or from uid
    private String connectionUid; // Zeek 'uid'
    private String note; // Zeek 'note' (alert type)

    // Network context
    private String sourceIp;
    private String destinationIp;
    private Integer sourcePort;
    private Integer destinationPort;
    private String protocol; // tcp/udp/icmp

    // Classification
    private String severity; // critical, high, medium, low
    private String msg; // Zeek 'msg' (description)
    private String sub; // Zeek 'sub' (subject/detail)

    // Threat scoring
    private Integer threatScore; // 0-100
    private Integer confidenceScore; // 0-100

    public ZeekAlertData() {
    }

    public ZeekAlertData(String connectionUid, String note, String sourceIp, String destinationIp) {
        this.connectionUid = connectionUid;
        this.note = note;
        this.sourceIp = sourceIp;
        this.destinationIp = destinationIp;
    }

    // Getters and Setters

    public String getAlertId() {
        return alertId;
    }

    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }

    public String getConnectionUid() {
        return connectionUid;
    }

    public void setConnectionUid(String connectionUid) {
        this.connectionUid = connectionUid;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getSub() {
        return sub;
    }

    public void setSub(String sub) {
        this.sub = sub;
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

    @Override
    public String toString() {
        return "ZeekAlertData{" +
                "uid='" + connectionUid + '\'' +
                ", note='" + note + '\'' +
                ", src='" + sourceIp + '\'' +
                ", dst='" + destinationIp + '\'' +
                ", msg='" + msg + '\'' +
                '}';
    }
}
