package com.yourorg.workflow;

import java.util.List;
import java.util.Map;

/**
 * Data model representing a workflow loaded from YAML.
 * Contains trigger conditions and execution steps.
 */
public class Workflow {
    private String name;
    private double version;
    private String description;
    private Trigger trigger;
    private List<Step> steps;
    
    // Constructors
    public Workflow() {}
    
    public Workflow(String name, double version, String description, Trigger trigger, List<Step> steps) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.trigger = trigger;
        this.steps = steps;
    }
    
    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public double getVersion() { return version; }
    public void setVersion(double version) { this.version = version; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public Trigger getTrigger() { return trigger; }
    public void setTrigger(Trigger trigger) { this.trigger = trigger; }
    
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = steps; }
    
    @Override
    public String toString() {
        return "Workflow{name='" + name + "', version=" + version + ", steps=" + (steps != null ? steps.size() : 0) + "}";
    }
    
    /**
     * Trigger definition with event type and condition
     */
    public static class Trigger {
        private String eventType;
        private String condition;
        
        public Trigger() {}
        
        public Trigger(String eventType, String condition) {
            this.eventType = eventType;
            this.condition = condition;
        }
        
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        
        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
    }
    
    /**
     * Workflow step containing an action to execute
     */
    public static class Step {
        private String name;
        private Action action;
        
        public Step() {}
        
        public Step(String name, Action action) {
            this.name = name;
            this.action = action;
        }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public Action getAction() { return action; }
        public void setAction(Action action) { this.action = action; }
    }
    
    /**
     * Action containing the event to publish
     */
    public static class Action {
        private String type;
        private Event event;
        
        public Action() {}
        
        public Action(String type, Event event) {
            this.type = type;
            this.event = event;
        }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public Event getEvent() { return event; }
        public void setEvent(Event event) { this.event = event; }
    }
    
    /**
     * Event to be published with type and data
     */
    public static class Event {
        private String type;
        private Map<String, Object> data;
        
        public Event() {}
        
        public Event(String type, Map<String, Object> data) {
            this.type = type;
            this.data = data;
        }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }
    }
}

