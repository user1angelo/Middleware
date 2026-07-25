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

        String expectedEventType = workflow.getTrigger().getEventType();
        if (expectedEventType != null && !expectedEventType.isEmpty()) {
            String actualEventType = alert.optString("event_type", "");
            if (!expectedEventType.equals(actualEventType)) {
                return false;
            }
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
        
        String alertType = payload.optString("alert_type", payload.optString("alertType", ""));
        String category = payload.optString("category", "");
        String noteType = payload.optString("note_type", payload.optString("noteType", ""));

        return alertType.toLowerCase().contains("ransomware") ||
               category.toLowerCase().contains("ransomware") ||
               noteType.toLowerCase().contains("ransomware") ||
               noteType.toLowerCase().contains("smb_mapping_event");
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
        
        // Check alert_type
        if (condition.contains("alert_type") || condition.contains("alertType")) {
            if (condition.contains("contains")) {
                String expectedValue = extractValueFromCondition(condition, "alert_type", "contains");
                if (expectedValue == null) {
                    expectedValue = extractValueFromCondition(condition, "alertType", "contains");
                }
                
                if (expectedValue != null) {
                    String alertType = payload.optString("alert_type", payload.optString("alertType", ""));
                    String category = payload.optString("category", "");
                    String noteType = payload.optString("note_type", payload.optString("noteType", ""));
                    
                    boolean matches = alertType.toLowerCase().contains(expectedValue.toLowerCase()) ||
                                      category.toLowerCase().contains(expectedValue.toLowerCase()) ||
                                      noteType.toLowerCase().contains(expectedValue.toLowerCase());
                    if (!matches) {
                        return false;
                    }
                }
            } else {
                String expectedValue = extractValueFromCondition(condition, "alert_type", "==");
                if (expectedValue == null) {
                    expectedValue = extractValueFromCondition(condition, "alertType", "==");
                }
                if (expectedValue != null) {
                    String alertType = payload.optString("alert_type", payload.optString("alertType", ""));
                    if (!alertType.equalsIgnoreCase(expectedValue)) {
                        return false;
                    }
                }
            }
        }
        
        // Check severity
        if (condition.contains("note_type") || condition.contains("noteType")) {
            if (condition.contains("contains")) {
                String expectedValue = extractValueFromCondition(condition, "note_type", "contains");
                if (expectedValue == null) {
                    expectedValue = extractValueFromCondition(condition, "noteType", "contains");
                }

                if (expectedValue != null) {
                    String noteType = payload.optString("note_type", payload.optString("noteType", ""));
                    if (!noteType.toLowerCase().contains(expectedValue.toLowerCase())) {
                        return false;
                    }
                }
            } else {
                String expectedValue = extractValueFromCondition(condition, "note_type", "==");
                if (expectedValue == null) {
                    expectedValue = extractValueFromCondition(condition, "noteType", "==");
                }

                if (expectedValue != null) {
                    String noteType = payload.optString("note_type", payload.optString("noteType", ""));
                    if (!noteType.equalsIgnoreCase(expectedValue)) {
                        return false;
                    }
                }
            }
        }

        // Check severity
        if (condition.contains("severity")) {
            if (condition.contains("!=")) {
                String severity = extractValueFromCondition(condition, "severity", "!=");
                if (severity != null) {
                    if (payload.has("severity") && payload.getString("severity").equalsIgnoreCase(severity)) {
                        return false;
                    }
                }
            } else {
                String severity = extractValueFromCondition(condition, "severity", "==");
                if (severity != null) {
                    if (!payload.has("severity") || !payload.getString("severity").equals(severity)) {
                        return false;
                    }
                }
            }
        }

        // Check signature
        if (condition.contains("signature")) {
            if (condition.contains("contains")) {
                // Handle contains operator with OR logic:
                // "signature contains 'A' or signature contains 'B'" →
                // match if actual signature contains A OR B
                if (!evaluateFieldContains(condition, payload, "signature")) {
                    return false;
                }
            } else {
                String signature = extractValueFromCondition(condition, "signature", "==");
                if (signature != null) {
                    if (!payload.has("signature") || !payload.getString("signature").equals(signature)) {
                        return false;
                    }
                }
            }
        }

        // Check Suricata signature id / SID
        if (condition.contains("signature_id") || condition.contains("signatureId") || condition.contains("sid")) {
            String expectedSignatureId = extractValueFromCondition(condition, "signature_id", "==");
            if (expectedSignatureId == null) {
                expectedSignatureId = extractValueFromCondition(condition, "signatureId", "==");
            }
            if (expectedSignatureId == null) {
                expectedSignatureId = extractValueFromCondition(condition, "sid", "==");
            }

            if (expectedSignatureId != null) {
                String actualSignatureId = payload.optString("signature_id",
                        payload.optString("signatureId", payload.optString("sid", "")));
                if (!actualSignatureId.equalsIgnoreCase(expectedSignatureId)) {
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

    /**
     * Evaluate "field contains 'value'" conditions with OR semantics.
     * Extracts all quoted values from "field contains '...'" patterns in
     * the condition string and returns true if the actual field value
     * contains ANY of them (implicit OR across clauses).
     * 
     * Note: if future conditions use "and" between multiple contains
     * clauses for the same field, this OR logic would be too permissive.
     * Current workflows only use "or" for multi-value signature matching.
     */
    private boolean evaluateFieldContains(String condition, JSONObject payload, String field) {
        if (!payload.has(field)) {
            return false;
        }
        String actualValue = payload.getString(field).toLowerCase();

        String pattern = field + " contains ";
        int pos = 0;
        while ((pos = condition.indexOf(pattern, pos)) != -1) {
            pos += pattern.length();
            while (pos < condition.length()
                    && condition.charAt(pos) != '\''
                    && condition.charAt(pos) != '"') {
                pos++;
            }
            if (pos >= condition.length()) {
                break;
            }
            char quote = condition.charAt(pos);
            pos++;
            int endPos = condition.indexOf(quote, pos);
            if (endPos == -1) {
                break;
            }
            String expectedValue = condition.substring(pos, endPos).toLowerCase();
            if (actualValue.contains(expectedValue)) {
                return true;
            }
            pos = endPos + 1;
        }
        return false;
    }
}

