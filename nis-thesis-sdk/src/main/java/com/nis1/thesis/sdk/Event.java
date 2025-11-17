package com.nis1.thesis.sdk;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Generic event envelope used across the SOAR framework for inter-module communication.
 * <p>
 * This class provides a standardized event structure that wraps any payload type with metadata
 * including unique identifier, timestamp, and event type for routing purposes.
 * </p>
 * <p>
 * Events are immutable once created and serve as the primary communication mechanism
 * between pluggable modules in the event-driven architecture.
 * </p>
 * 
 * @param <T> The type of the payload data contained within this event
 * @author NIS1
 * @version 1.0
 * @since September 10, 2025
 */
public final class Event<T> {
    private final String id;
    private final Instant timestamp;
    private final String type;
    private final T data;

    /**
     * Constructs a new Event with the specified parameters.
     * 
     * @param id Unique identifier for this event, must not be null
     * @param timestamp The exact time when this event was created, must not be null
     * @param type The event type used for routing and filtering, must not be null
     * @param data The payload data for this event, must not be null
     * @throws NullPointerException if any parameter is null
     */
    public Event(String id, Instant timestamp, String type, T data) {
        this.id = Objects.requireNonNull(id, "id");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.type = Objects.requireNonNull(type, "type");
        this.data = Objects.requireNonNull(data, "data");
    }

    /**
     * Convenience factory method that creates an Event with auto-generated ID and current timestamp.
     * <p>
     * This method simplifies event creation by automatically generating a unique UUID
     * and using the current system time as the timestamp.
     * </p>
     * 
     * @param <T> The type of the payload data
     * @param type The event type used for routing and filtering, must not be null
     * @param data The payload data for this event, must not be null
     * @return A new Event instance with generated ID and current timestamp
     * @throws NullPointerException if type or data is null
     */
    public static <T> Event<T> of(String type, T data) {
        return new Event<>(UUID.randomUUID().toString(), Instant.now(), type, data);
    }

    /**
     * Returns the unique identifier for this event.
     * 
     * @return The event's unique ID
     */
    public String getId() { return id; }
    
    /**
     * Returns the timestamp when this event was created.
     * 
     * @return The event's creation timestamp
     */
    public Instant getTimestamp() { return timestamp; }
    
    /**
     * Returns the event type used for routing and filtering.
     * 
     * @return The event's type string
     */
    public String getType() { return type; }
    
    /**
     * Returns the payload data contained within this event.
     * 
     * @return The event's payload data
     */
    public T getData() { return data; }
}
