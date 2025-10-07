package com.yourorg.workflow;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches alerts against workflow trigger conditions.
 * Current implementation uses placeholder/simplified matching logic.
 * 
 * For production, implement full expression evaluation.
 */
public class WorkflowMatcher {
    
    /**
     * Find all workflows that match the given alert
     */
    public List<Workflow> findMatchingWorkflows(JSONObject alert, List<Workflow> workflows) {
        List<Workflow> matches = new ArrayList<>();
        
        for (Workflow workflow : workflows) {
            if (matchesWorkflow(alert, workflow)) {
                matches.add(workflow);
            }
        }
        
        return matches;
    }
    
    /**
     * Check if alert matches a specific workflow's trigger condition
     */
    private boolean matchesWorkflow(JSONObject alert, Workflow workflow) {
        if (workflow.getTrigger() == null) {
            return false;
        }
        
        String condition = workflow.getTrigger().getCondition();
        if (condition == null || condition.isEmpty()) {
            // No condition means always match
            return true;
        }
        
        // Check if this is the general workflow
        if (workflow.getName().toLowerCase().contains("general")) {
            // General workflow always matches if alert type is ransomware
            return isRansomwareAlert(alert);
        }
        
        // For specific workflows, evaluate condition
        return evaluateCondition(condition, alert);
    }
    
    /**
     * Check if alert is ransomware-related
     */
    private boolean isRansomwareAlert(JSONObject alert) {
        if (!alert.has("payload")) {
            return false;
        }
        
        JSONObject payload = alert.getJSONObject("payload");
        if (!payload.has("alert_type")) {
            return false;
        }
        
        String alertType = payload.getString("alert_type");
        return alertType.toLowerCase().contains("ransomware");
    }
    
    /**
     * Evaluate condition against alert (placeholder implementation)
     * 
     * For production, use proper expression parser/evaluator.
     * Current logic: simple field matching
     */
    private boolean evaluateCondition(String condition, JSONObject alert) {
        // Extract payload
        if (!alert.has("payload")) {
            return false;
        }
        
        JSONObject payload = alert.getJSONObject("payload");
        
        // Parse condition for field checks
        // Example: "{{ trigger.payload.severity == 'high' and trigger.payload.threat_score >= 70 }}"
        
        // Check severity
        if (condition.contains("severity")) {
            String severity = extractValueFromCondition(condition, "severity", "==");
            if (severity != null) {
                if (!payload.has("severity") || !payload.getString("severity").equals(severity)) {
                    return false;
                }
            }
        }
        
        // Check signature
        if (condition.contains("signature")) {
            String signature = extractValueFromCondition(condition, "signature", "==");
            if (signature != null) {
                if (!payload.has("signature") || !payload.getString("signature").equals(signature)) {
                    return false;
                }
            }
        }
        
        // Check threat_score
        if (condition.contains("threat_score")) {
            Integer threshold = extractNumberFromCondition(condition, "threat_score", ">=");
            if (threshold != null) {
                if (!payload.has("threat_score")) {
                    return false;
                }
                
                int threatScore = payload.optInt("threat_score", 0);
                if (threatScore < threshold) {
                    return false;
                }
            }
        }
        
        // If all checks passed (or no checks found), condition matches
        return true;
    }
    
    /**
     * Extract string value from condition
     * Example: "severity == 'high'" → returns "high"
     */
    private String extractValueFromCondition(String condition, String field, String operator) {
        String pattern = field + " " + operator + " ";
        int startIndex = condition.indexOf(pattern);
        if (startIndex == -1) {
            // Try without spaces
            pattern = field + operator;
            startIndex = condition.indexOf(pattern);
            if (startIndex == -1) {
                return null;
            }
        }
        
        startIndex = condition.indexOf('\'', startIndex);
        if (startIndex == -1) {
            // Try double quotes
            startIndex = condition.indexOf('"', startIndex);
            if (startIndex == -1) {
                return null;
            }
        }
        
        int endIndex = condition.indexOf('\'', startIndex + 1);
        if (endIndex == -1) {
            endIndex = condition.indexOf('"', startIndex + 1);
            if (endIndex == -1) {
                return null;
            }
        }
        
        return condition.substring(startIndex + 1, endIndex);
    }
    
    /**
     * Extract number value from condition
     * Example: "threat_score >= 70" → returns 70
     */
    private Integer extractNumberFromCondition(String condition, String field, String operator) {
        String pattern = field + " " + operator + " ";
        int startIndex = condition.indexOf(pattern);
        if (startIndex == -1) {
            // Try without spaces
            pattern = field + operator;
            startIndex = condition.indexOf(pattern);
            if (startIndex == -1) {
                return null;
            }
        }
        
        startIndex += pattern.length();
        
        // Find the number
        StringBuilder numberStr = new StringBuilder();
        for (int i = startIndex; i < condition.length(); i++) {
            char c = condition.charAt(i);
            if (Character.isDigit(c)) {
                numberStr.append(c);
            } else if (numberStr.length() > 0) {
                break;
            }
        }
        
        if (numberStr.length() == 0) {
            return null;
        }
        
        try {
            return Integer.parseInt(numberStr.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

