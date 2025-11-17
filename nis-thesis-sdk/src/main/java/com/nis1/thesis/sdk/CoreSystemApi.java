package com.nis1.thesis.sdk;

import java.util.function.Consumer;

/**
 * API exposed by the Core System to all pluggable modules.
 * <p>
 * This interface provides both publishing and subscription capabilities for the event-driven architecture.
 * It serves as the secure gateway to the Communication Fabric, abstracting away the underlying
 * AMQP protocols and message broker complexities.
 * </p>
 * <p>
 * All inter-module communication in the SOAR framework occurs through this API, ensuring
 * standardized event handling and reliable message delivery.
 * </p>
 *
 * @author NIS1
 * @version 1.0
 * @since September 10, 2025
 */
public interface CoreSystemApi {
    
    /**
     * Publishes an event to the Communication Fabric.
     * <p>
     * Events are published to a topic exchange using the event's type as the routing key.
     * The event will be serialized to JSON and delivered to all subscribers that match
     * the event type pattern.
     * </p>
     *
     * @param event The event to publish, containing type and payload data.
     *              Must not be null.
     * @throws IllegalArgumentException if event is null
     * @throws RuntimeException if publishing fails due to network or broker issues
     */
    void publishEvent(Event<?> event);
    
    /**
     * Subscribes to events of a specific type or pattern.
     * <p>
     * Supports AMQP topic patterns including wildcards:
     * <ul>
     *   <li>* (asterisk) matches exactly one word</li>
     *   <li># (hash) matches zero or more words</li>
     * </ul>
     * Examples:
     * <ul>
     *   <li>"alerts.host.wazuh" - exact match</li>
     *   <li>"alerts.host.*" - all host alerts</li>
     *   <li>"alerts.#" - all alert types</li>
     *   <li>"#" - all events (use with caution)</li>
     * </ul>
     *
     * @param eventType The event type or pattern to subscribe to.
     *                  Must not be null or empty.
     * @param listener The callback function to handle received events.
     *                 Must not be null.
     * @throws IllegalArgumentException if eventType is null/empty or listener is null
     * @throws RuntimeException if subscription fails due to network or broker issues
     */
    void subscribeToEvent(String eventType, Consumer<Event<?>> listener);
}
