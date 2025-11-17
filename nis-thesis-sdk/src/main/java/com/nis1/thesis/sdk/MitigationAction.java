package com.nis1.thesis.sdk;

/**
 * Enumeration of available mitigation actions for automated security responses.
 * <p>
 * This enum defines the standard set of mitigation actions that can be performed
 * by response modules in the SOAR framework. Each action represents a specific
 * security response capability that can be initiated through mitigation commands.
 * </p>
 * <p>
 * The available actions range from network-level controls (IP blocking, traffic redirection)
 * to endpoint actions (process termination, user account management) and organizational
 * responses (quarantine, alerting).
 * </p>
 *
 * @author NIS1
 * @version 1.0
 * @since September 10, 2025
 */
public enum MitigationAction {
    /** Isolate the affected host by moving it to a quarantine network segment */
    QUARANTINE("QUARANTINE"),
    
    /** Block network traffic from a specific IP address at firewall or network level */
    BLOCK_IP("BLOCK_IP"),
    
    /** Apply rate limiting to reduce impact of potential attack traffic */
    RATE_LIMIT("RATE_LIMIT"),
    
    /** Redirect suspicious traffic to a honeypot or analysis system */
    REDIRECT_TRAFFIC("REDIRECT_TRAFFIC"),
    
    /** Move the affected host to an isolated VLAN for containment */
    ISOLATE_VLAN("ISOLATE_VLAN"),
    
    /** Generate alert notifications without taking automated action */
    ALERT_ONLY("ALERT_ONLY"),
    
    /** Disable a user account to prevent further access */
    DISABLE_USER("DISABLE_USER"),
    
    /** Terminate a specific process on the affected host */
    KILL_PROCESS("KILL_PROCESS");

    private final String action;

    /**
     * Constructs a MitigationAction with the specified action string.
     * 
     * @param action The string representation of this mitigation action
     */
    MitigationAction(String action) {
        this.action = action;
    }

    /**
     * Returns the string representation of this mitigation action.
     * 
     * @return The action string
     */
    public String getAction() {
        return action;
    }

    /**
     * Returns the string representation of this mitigation action.
     * 
     * @return The action string
     */
    @Override
    public String toString() {
        return action;
    }
}