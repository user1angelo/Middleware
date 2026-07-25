package com.nis1.thesis.sdk;

import com.fatboyindustrial.gsonjavatime.Converters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip serialize/deserialize tests for Event<T>.
 * <p>
 * Documents a real compatibility gap found while writing these tests: on this JDK
 * (module system blocks reflective access to java.time internals), a plain
 * {@code new Gson()} - the exact configuration actually used at runtime in
 * SdkModuleHost.publishEvent - CANNOT serialize Event's {@code Instant timestamp} field
 * at all; it throws JsonIOException. This module declares gson-javatime-serialisers as a
 * dependency specifically to solve this, but nothing in the source tree actually wires it
 * into a GsonBuilder - SdkModuleHost only ever calls {@code gson.toJson(event.getData())}
 * (the payload alone, never the full Event), which is why this has never surfaced as a
 * runtime failure despite being a real latent bug for any future code that tries to
 * serialize a whole Event.
 */
class EventTest {

    private final Gson plainGson = new Gson();
    private final Gson javaTimeAwareGson = Converters.registerInstant(new GsonBuilder()).create();

    @Test
    void plainGsonCannotSerializeEventDueToInstantField() {
        Event<NidsAlertData> event = Event.of("NIDS_ALERT", new NidsAlertData());
        assertThrows(JsonIOException.class, () -> plainGson.toJson(event));
    }

    @Test
    void roundTripsGenericPayloadWithJavaTimeAwareGson() {
        NidsAlertData original = new NidsAlertData("10.0.0.1", "10.0.0.2", "ET SCAN", "high");
        Event<NidsAlertData> event = Event.of("NIDS_ALERT", original);

        String json = javaTimeAwareGson.toJson(event);

        Type eventOfNidsAlertData = TypeToken.getParameterized(Event.class, NidsAlertData.class).getType();
        Event<NidsAlertData> restored = javaTimeAwareGson.fromJson(json, eventOfNidsAlertData);

        assertEquals(event.getId(), restored.getId());
        assertEquals(event.getType(), restored.getType());
        assertEquals(event.getTimestamp(), restored.getTimestamp());
        assertNotNull(restored.getData());
        assertEquals(original.getSourceIp(), restored.getData().getSourceIp());
        assertEquals(original.getDestinationIp(), restored.getData().getDestinationIp());
        assertEquals(original.getSignature(), restored.getData().getSignature());
        assertEquals(original.getSignatureSeverity(), restored.getData().getSignatureSeverity());
    }

    @Test
    void ofFactoryGeneratesNonNullIdAndTimestamp() {
        Event<NidsAlertData> event = Event.of("NIDS_ALERT", new NidsAlertData());
        assertNotNull(event.getId());
        assertFalse(event.getId().isBlank());
        assertNotNull(event.getTimestamp());
    }

    @Test
    void constructorRejectsNullId() {
        assertThrows(NullPointerException.class,
                () -> new Event<>(null, Instant.now(), "NIDS_ALERT", new NidsAlertData()));
    }

    @Test
    void constructorRejectsNullData() {
        assertThrows(NullPointerException.class,
                () -> new Event<>("id-1", Instant.now(), "NIDS_ALERT", null));
    }

    /**
     * Documents a real compatibility gap: Gson deserializes Event<T> via reflection
     * (bypassing the constructor entirely, since there is no no-arg constructor), so the
     * constructor's Objects.requireNonNull checks do NOT protect deserialized instances.
     * A JSON payload missing "data" produces an Event with data == null rather than throwing,
     * unlike constructing one directly via `new Event<>(...)`.
     */
    @Test
    void deserializationBypassesConstructorNullChecksForMissingData() {
        String jsonMissingData = "{\"id\":\"id-1\",\"timestamp\":\"2026-07-25T00:00:00Z\",\"type\":\"NIDS_ALERT\"}";

        Type eventOfNidsAlertData = TypeToken.getParameterized(Event.class, NidsAlertData.class).getType();
        Event<NidsAlertData> restored = javaTimeAwareGson.fromJson(jsonMissingData, eventOfNidsAlertData);

        assertNotNull(restored);
        assertEquals("id-1", restored.getId());
        assertNull(restored.getData());
    }
}
