package com.yourorg.workflow;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads workflow definitions from YAML files.
 * Note: This is a simplified implementation that expects YAML-like structure
 * but parses as JSON for simplicity (avoiding external YAML library dependency).
 * 
 * For production, consider using SnakeYAML library.
 */
public class WorkflowLoader {
    
    /**
     * Load all workflow files from specified directory
     */
    public List<Workflow> loadWorkflows(String directory) {
        List<Workflow> workflows = new ArrayList<>();
        File dir = new File(directory);
        
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("⚠️  Workflow directory not found: " + directory);
            return workflows;
        }
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files == null || files.length == 0) {
            System.out.println("📭 No workflow files found in: " + directory);
            return workflows;
        }
        
        System.out.println("📂 Found " + files.length + " workflow file(s)");
        
        for (File file : files) {
            try {
                Workflow workflow = loadWorkflowFromFile(file);
                if (workflow != null) {
                    workflows.add(workflow);
                    System.out.println("   ✓ Loaded: " + file.getName() + " (" + workflow.getName() + ")");
                }
            } catch (Exception e) {
                System.err.println("   ⚠️  Failed to load " + file.getName() + ": " + e.getMessage());
            }
        }
        
        return workflows;
    }
    
    /**
     * Load a single workflow from file
     * Note: Expects simplified YAML-like JSON format for now
     */
    private Workflow loadWorkflowFromFile(File file) throws IOException {
        // Read file content
        String content = Files.readString(file.toPath());
        
        // For simplified implementation, we'll parse key-value pairs manually
        // In production, use proper YAML parser
        Workflow workflow = new Workflow();
        
        // Parse name
        workflow.setName(extractValue(content, "name:"));
        
        // Parse version
        String versionStr = extractValue(content, "version:");
        try {
            workflow.setVersion(Double.parseDouble(versionStr));
        } catch (NumberFormatException e) {
            workflow.setVersion(1.0);
        }
        
        // Parse description
        workflow.setDescription(extractMultilineValue(content, "description:"));
        
        // Parse trigger
        Workflow.Trigger trigger = new Workflow.Trigger();
        trigger.setEventType(extractValue(content, "event_type:"));
        trigger.setCondition(extractQuotedValue(content, "condition:"));
        workflow.setTrigger(trigger);
        
        // Parse steps (simplified)
        List<Workflow.Step> steps = parseSteps(content);
        workflow.setSteps(steps);
        
        return workflow;
    }
    
    /**
     * Extract simple value from YAML content
     */
    private String extractValue(String content, String key) {
        int startIndex = content.indexOf(key);
        if (startIndex == -1) return "";
        
        startIndex += key.length();
        int endIndex = content.indexOf('\n', startIndex);
        if (endIndex == -1) endIndex = content.length();
        
        String value = content.substring(startIndex, endIndex).trim();
        // Remove quotes if present
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
    
    /**
     * Extract quoted value (handles template expressions)
     */
    private String extractQuotedValue(String content, String key) {
        int startIndex = content.indexOf(key);
        if (startIndex == -1) return "";
        
        startIndex = content.indexOf('"', startIndex);
        if (startIndex == -1) return "";
        
        int endIndex = content.indexOf('"', startIndex + 1);
        if (endIndex == -1) return "";
        
        return content.substring(startIndex + 1, endIndex);
    }
    
    /**
     * Extract multiline value (like description)
     */
    private String extractMultilineValue(String content, String key) {
        int startIndex = content.indexOf(key);
        if (startIndex == -1) return "";
        
        // Find the > character indicating multiline
        int multilineStart = content.indexOf('>', startIndex);
        if (multilineStart == -1) {
            return extractValue(content, key);
        }
        
        // Read until next key (identified by something: pattern)
        int endIndex = content.indexOf("\n\n", multilineStart);
        if (endIndex == -1) {
            // Look for next key pattern
            endIndex = content.indexOf("\ntrigger:", multilineStart);
            if (endIndex == -1) endIndex = content.length();
        }
        
        String multiline = content.substring(multilineStart + 1, endIndex).trim();
        return multiline.replaceAll("\\s+", " ");
    }
    
    /**
     * Parse steps section (simplified)
     */
    private List<Workflow.Step> parseSteps(String content) {
        List<Workflow.Step> steps = new ArrayList<>();
        
        int stepsIndex = content.indexOf("steps:");
        if (stepsIndex == -1) return steps;
        
        // Find all step entries (marked by "- name:")
        int currentPos = stepsIndex;
        while (true) {
            int stepStart = content.indexOf("- name:", currentPos);
            if (stepStart == -1) break;
            
            // Find next step or end of file
            int nextStep = content.indexOf("- name:", stepStart + 7);
            String stepContent;
            if (nextStep == -1) {
                stepContent = content.substring(stepStart);
            } else {
                stepContent = content.substring(stepStart, nextStep);
            }
            
            // Parse this step
            Workflow.Step step = parseStep(stepContent);
            if (step != null) {
                steps.add(step);
            }
            
            currentPos = stepStart + 7;
            if (nextStep == -1) break;
        }
        
        return steps;
    }
    
    /**
     * Parse a single step
     */
    private Workflow.Step parseStep(String stepContent) {
        Workflow.Step step = new Workflow.Step();
        
        // Extract step name
        String stepName = extractQuotedValue(stepContent, "- name:");
        if (stepName.isEmpty()) {
            stepName = extractValue(stepContent, "- name:");
        }
        step.setName(stepName);
        
        // Create action
        Workflow.Action action = new Workflow.Action();
        action.setType(extractValue(stepContent, "type:"));
        
        // Create event
        Workflow.Event event = new Workflow.Event();
        event.setType(extractValue(stepContent, "type:"));
        
        // Parse event data (simplified - store as map)
        Map<String, Object> eventData = new HashMap<>();
        // In a full implementation, would parse the data: section properly
        // For now, just mark it as placeholder
        eventData.put("_placeholder", "Event data parsed from YAML");
        event.setData(eventData);
        
        action.setEvent(event);
        step.setAction(action);
        
        return step;
    }
    
    /**
     * Find general workflow by name pattern
     */
    public Workflow findGeneralWorkflow(List<Workflow> workflows) {
        for (Workflow workflow : workflows) {
            if (workflow.getName().toLowerCase().contains("general")) {
                return workflow;
            }
        }
        return null;
    }
}

