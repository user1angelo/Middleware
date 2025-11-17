package com.nis1.thesis.sdk;

/**
 * Data payload for mitigation command events sent to automated response modules.
 * <p>
 * This class represents commands for initiating automated security response actions
 * within the SOAR framework. It contains all necessary information for response modules
 * to execute specific mitigation actions against identified threats.
 * </p>
 * <p>
 * Mitigation commands typically include target identification, the specific action
 * to perform, and contextual information such as justification and priority levels
 * for proper execution and audit trails.
 * </p>
 *
 * @author NIS1
 * @version 1.0
 * @since September 10, 2025
 */
public class MitigationCommandData {
    private String targetHost;
    private MitigationAction action;
    private String justification;
    private String workflowInstanceId;
    private Integer priority;
    private String additionalParameters;

    /**
     * Default constructor for JSON deserialization.
     */
    public MitigationCommandData() {}

    /**
     * Constructs a mitigation command with basic target and action information.
     * 
     * @param targetHost The hostname or IP address of the target system
     * @param action The specific mitigation action to perform
     */
    public MitigationCommandData(String targetHost, MitigationAction action) {
        this.targetHost = targetHost;
        this.action = action;
    }

    /**
     * Constructs a mitigation command with target, action, and justification information.
     * 
     * @param targetHost The hostname or IP address of the target system
     * @param action The specific mitigation action to perform
     * @param justification Human-readable explanation for why this action was chosen
     */
    public MitigationCommandData(String targetHost, MitigationAction action, String justification) {
        this.targetHost = targetHost;
        this.action = action;
        this.justification = justification;
    }

    /**
     * Returns the hostname or IP address of the target system.
     * 
     * @return The target host identifier
     */
    public String getTargetHost() { return targetHost; }
    
    /**
     * Sets the hostname or IP address of the target system.
     * 
     * @param targetHost The target host identifier
     */
    public void setTargetHost(String targetHost) { this.targetHost = targetHost; }

    /**
     * Returns the specific mitigation action to perform.
     * 
     * @return The mitigation action
     */
    public MitigationAction getAction() { return action; }
    
    /**
     * Sets the specific mitigation action to perform.
     * 
     * @param action The mitigation action
     */
    public void setAction(MitigationAction action) { this.action = action; }

    /**
     * Returns the human-readable justification for this mitigation command.
     * 
     * @return The justification text
     */
    public String getJustification() { return justification; }
    
    /**
     * Sets the human-readable justification for this mitigation command.
     * 
     * @param justification The justification text
     */
    public void setJustification(String justification) { this.justification = justification; }

    /**
     * Returns the workflow instance ID that generated this command.
     * 
     * @return The workflow instance identifier
     */
    public String getWorkflowInstanceId() { return workflowInstanceId; }
    
    /**
     * Sets the workflow instance ID that generated this command.
     * 
     * @param workflowInstanceId The workflow instance identifier
     */
    public void setWorkflowInstanceId(String workflowInstanceId) { this.workflowInstanceId = workflowInstanceId; }

    /**
     * Returns the priority level for executing this mitigation command.
     * 
     * @return The priority level (higher numbers indicate higher priority)
     */
    public Integer getPriority() { return priority; }
    
    /**
     * Sets the priority level for executing this mitigation command.
     * 
     * @param priority The priority level (higher numbers indicate higher priority)
     */
    public void setPriority(Integer priority) { this.priority = priority; }

    /**
     * Returns additional parameters for the mitigation action in JSON or key-value format.
     * 
     * @return Additional parameters string
     */
    public String getAdditionalParameters() { return additionalParameters; }
    
    /**
     * Sets additional parameters for the mitigation action in JSON or key-value format.
     * 
     * @param additionalParameters Additional parameters string
     */
    public void setAdditionalParameters(String additionalParameters) { this.additionalParameters = additionalParameters; }
}