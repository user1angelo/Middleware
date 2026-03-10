package com.yourorg.odl.enforcer.api;

/**
 * Interface for handling ODL workflow commands.
 */
public interface CommandHandler {
    
    /**
     * Process a workflow command.
     * 
     * @param command The workflow command to process
     * @return true if command was handled successfully, false otherwise
     */
    boolean handleCommand(WorkflowCommand command);
    
    /**
     * Check if this handler can process the given command type.
     * 
     * @param messageType The message type (e.g., "odl.flow.add")
     * @return true if this handler supports the message type
     */
    boolean canHandle(String messageType);
}
