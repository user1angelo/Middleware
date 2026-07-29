package com.nis1.thesis.sdk;

import java.util.HashMap;
import java.util.Map;

/**
 * Typed replacement for {@link MitigationCommandData}'s previous raw-{@code String}
 * {@code additionalParameters} field. Fields mirror exactly what
 * {@code SdkModuleHost.parseMitigationCommand} populates and what
 * {@code OpenDaylightModule.onMitigationCommand}/{@code onRemoveMitigation} actually read -
 * see SDK_USABILITY_AUDIT.md, Role Expressiveness dimension, for why an opaque JSON string was
 * a problem: a reader could not tell what shape/fields to expect without reading the receiving
 * module's parser.
 * <p>
 * {@code telemetry} and {@code lifecycle} stay loosely typed ({@code Map<String,Object>}) since
 * their fields grow independently over time (e.g. new telemetry keys added by
 * {@code StageTimer}-style instrumentation) - forcing a rigid schema on those would just
 * reintroduce a different version of the same problem.
 */
public class MitigationParameters {

    private String eventType;
    private String messageType;
    private String macAddress;
    private String mitigationId;
    private String rollbackScope;
    private String rollbackRequestSource;
    private String rollbackReason;
    private String severity;
    private QuarantinePolicy quarantinePolicy;
    private Map<String, Object> telemetry = new HashMap<>();
    private Map<String, Object> lifecycle = new HashMap<>();
    private final Map<String, Object> extra = new HashMap<>();

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getMitigationId() {
        return mitigationId;
    }

    public void setMitigationId(String mitigationId) {
        this.mitigationId = mitigationId;
    }

    public String getRollbackScope() {
        return rollbackScope;
    }

    public void setRollbackScope(String rollbackScope) {
        this.rollbackScope = rollbackScope;
    }

    public String getRollbackRequestSource() {
        return rollbackRequestSource;
    }

    public void setRollbackRequestSource(String rollbackRequestSource) {
        this.rollbackRequestSource = rollbackRequestSource;
    }

    public String getRollbackReason() {
        return rollbackReason;
    }

    public void setRollbackReason(String rollbackReason) {
        this.rollbackReason = rollbackReason;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public QuarantinePolicy getQuarantinePolicy() {
        return quarantinePolicy;
    }

    public void setQuarantinePolicy(QuarantinePolicy quarantinePolicy) {
        this.quarantinePolicy = quarantinePolicy;
    }

    public Map<String, Object> getTelemetry() {
        return telemetry;
    }

    public void setTelemetry(Map<String, Object> telemetry) {
        this.telemetry = telemetry != null ? telemetry : new HashMap<>();
    }

    public Map<String, Object> getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(Map<String, Object> lifecycle) {
        this.lifecycle = lifecycle != null ? lifecycle : new HashMap<>();
    }

    /** Any other ad-hoc field that arrived in the originating payload but has no dedicated field here. */
    public Object getExtra(String key) {
        return extra.get(key);
    }

    public void putExtra(String key, Object value) {
        extra.put(key, value);
    }

    public Map<String, Object> getExtraFields() {
        return extra;
    }

    /**
     * Typed replacement for the previously untyped {@code quarantine_policy} nested JSON object -
     * fields match exactly what {@code OpenDaylightModule.buildPolicyOptions} already reads
     * (previously via {@code JSONObject.optString}/{@code optBoolean}).
     * <p>
     * {@code containArp}/{@code containDhcp} are boxed {@link Boolean}, not primitive - {@code null}
     * means "not specified, use the module's own default," which primitive {@code boolean} cannot
     * represent (it would force {@code false} whenever the field was merely absent from the
     * incoming payload, silently overriding whatever default the module was configured with).
     */
    public static class QuarantinePolicy {
        private String mode;
        private Boolean containArp;
        private Boolean containDhcp;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Boolean getContainArp() {
            return containArp;
        }

        public void setContainArp(Boolean containArp) {
            this.containArp = containArp;
        }

        public Boolean getContainDhcp() {
            return containDhcp;
        }

        public void setContainDhcp(Boolean containDhcp) {
            this.containDhcp = containDhcp;
        }
    }
}
