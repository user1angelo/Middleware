package com.yourorg.odl.enforcer.impl.handlers;

import com.yourorg.odl.enforcer.api.CommandHandler;
import com.yourorg.odl.enforcer.api.WorkflowCommand;
import com.yourorg.odl.enforcer.impl.ConfigLoader;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles flow addition commands via OpenFlow.
 * Command format:
 * {
 *   "message_type": "odl.flow.add",
 *   "payload": {
 *     "node_id": "openflow:1",
 *     "table_id": 0,
 *     "priority": 100,
 *     "match": {
 *       "eth_type": "0x0800",
 *       "ipv4_src": "10.0.0.1",
 *       "ipv4_dst": "10.0.0.2"
 *     },
 *     "actions": ["output:2"],
 *     "idle_timeout": 0,
 *     "hard_timeout": 0
 *   }
 * }
 */
public class FlowAddHandler implements CommandHandler {
    private static final Logger LOG = LoggerFactory.getLogger(FlowAddHandler.class);
    private static final String COMMAND_TYPE = "odl.flow.add";
    
    private final ConfigLoader config;
    // TODO: Inject MD-SAL DataBroker when integrating with ODL
    
    public FlowAddHandler(ConfigLoader config) {
        this.config = config;
    }
    
    @Override
    public boolean canHandle(String messageType) {
        return COMMAND_TYPE.equals(messageType);
    }
    
    @Override
    public boolean handleCommand(WorkflowCommand command) {
        try {
            JSONObject payload = command.getPayload();
            
            String nodeId = payload.getString("node_id");
            int tableId = payload.optInt("table_id", 0);
            int priority = payload.optInt("priority", config.getDefaultFlowPriority());
            JSONObject match = payload.optJSONObject("match");
            int idleTimeout = payload.optInt("idle_timeout", config.getFlowIdleTimeout());
            int hardTimeout = payload.optInt("hard_timeout", config.getFlowHardTimeout());
            
            LOG.info("Adding flow to node {} (Event: {})", nodeId, command.getEventId());
            LOG.info("  Table: {}, Priority: {}", tableId, priority);
            LOG.info("  Match: {}", match != null ? match.toString() : "any");
            
            boolean success = installFlow(nodeId, tableId, priority, match, 
                                         payload.optJSONArray("actions"), 
                                         idleTimeout, hardTimeout);
            
            if (success) {
                LOG.info("Successfully added flow (Event: {})", command.getEventId());
            } else {
                LOG.error("Failed to add flow (Event: {})", command.getEventId());
            }
            
            return success;
            
        } catch (Exception e) {
            LOG.error("Error handling flow add command: {}", e.getMessage(), e);
            return false;
        }
    }
    
    private boolean installFlow(String nodeId, int tableId, int priority,
                               JSONObject match, Object actions,
                               int idleTimeout, int hardTimeout) {
        try {
            // For now, log the flow details
            // In production, this would use MD-SAL DataBroker to write the flow
            LOG.info("Installing flow:");
            LOG.info("  Node: {}", nodeId);
            LOG.info("  Table: {}", tableId);
            LOG.info("  Priority: {}", priority);
            LOG.info("  Idle Timeout: {}", idleTimeout);
            LOG.info("  Hard Timeout: {}", hardTimeout);
            
            if (match != null) {
                LOG.info("  Match criteria:");
                match.keys().forEachRemaining(key -> 
                    LOG.info("    {}: {}", key, match.get(key))
                );
            }
            
            if (actions != null) {
                LOG.info("  Actions: {}", actions);
            }
            
            LOG.info("Flow installed successfully (simulated)");
            return true;
            
        } catch (Exception e) {
            LOG.error("Error installing flow: {}", e.getMessage(), e);
            return false;
        }
    }
}
