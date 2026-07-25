package com.nis1.thesis.sdk;

import java.util.function.Consumer;

/**
 * API exposed by the Core System to all embedded (in-process) pluggable modules.
 * <p>
 * This interface provides both publishing and subscription capabilities for the event-driven
 * architecture. It is implemented by the Module Registry's {@code SdkModuleHost}, which holds
 * embedded modules and dispatches events to them in-memory, and which forwards a subset of
 * published events onward to RabbitMQ.
 * </p>
 * <p>
 * There is no AMQP topic exchange or routing-key matching anywhere in this path: publishing
 * and subscription are both implemented as plain in-memory dispatch keyed by the event's
 * {@code type} string, and the one RabbitMQ hop involved uses a single named, durable queue via
 * the default exchange ({@code channel.basicPublish("", queueName, ...)}). Standalone
 * (non-embedded) modules communicate purely over RabbitMQ named queues and never go through
 * this interface at all.
 * </p>
 *
 * @author NIS1
 * @version 1.0
 * @since September 10, 2025
 */
public interface CoreSystemApi {

    /**
     * Publishes an event from an embedded module.
     * <p>
     * The current implementation only forwards events whose {@code type} starts with the
     * literal prefix {@code "alerts."}; events with any other type are accepted but silently
     * dropped (not forwarded, not queued, not dispatched to subscribers). Forwarded events are
     * serialized to JSON and published with {@code basicPublish("", udmIngressQueue, ...)} to a
     * single fixed named queue on RabbitMQ's default exchange — there is no exchange, routing
     * key, or per-type queue involved.
     * </p>
     *
     * @param event The event to publish, containing type and payload data.
     *              Must not be null.
     * @throws IllegalArgumentException if event is null
     * @throws RuntimeException if publishing fails due to network or broker issues
     */
    void publishEvent(Event<?> event);

    /**
     * Subscribes an embedded module to events of a specific type or prefix.
     * <p>
     * This is in-memory dispatch, not AMQP topic routing. Registered listeners are matched
     * against dispatched events in two ways only:
     * <ul>
     *   <li>Exact match — {@code eventType} equals the event's {@code type} exactly.</li>
     *   <li>Single-level prefix wildcard — an {@code eventType} ending in {@code ".*"} (e.g.
     *       {@code "odl.*"}) matches any event type starting with that prefix (e.g.
     *       {@code "odl.host.isolate"}).</li>
     * </ul>
     * Full AMQP topic algebra (multi-segment {@code #} wildcards, {@code *} matching a single
     * arbitrary segment anywhere in the pattern) is <strong>not</strong> supported.
     * </p>
     *
     * @param eventType The exact event type, or a {@code "prefix.*"} pattern, to subscribe to.
     *                  Must not be null or empty.
     * @param listener The callback function to handle received events.
     *                 Must not be null.
     * @throws IllegalArgumentException if eventType is null/empty or listener is null
     * @throws RuntimeException if subscription fails due to network or broker issues
     */
    void subscribeToEvent(String eventType, Consumer<Event<?>> listener);
}
