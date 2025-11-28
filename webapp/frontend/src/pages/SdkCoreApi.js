import React from 'react';

const SdkCoreApi = () => {
  return (
    <div className="card">
      <h2 className="card-title">Core SDK Interfaces</h2>
      <p>
        This section gives a <strong>method-by-method</strong> explanation of the most important
        SDK types, based on the SDK Detailed Context documentation.
      </p>

      <h3>PluggableModule</h3>
      <p>
        Every user-defined module must implement <code>PluggableModule</code>. The framework
        uses this as the lifecycle contract.
      </p>
      <pre className="code-block">
{`public interface PluggableModule {
    String getName();
    void initialize(CoreSystemApi api);
    void shutdown();
}`}
      </pre>
      <ul>
        <li>
          <code>String getName()</code> – Human-readable name shown in logs, dashboards, and
          module registries.
        </li>
        <li>
          <code>void initialize(CoreSystemApi api)</code> – Called exactly once when the module
          is loaded. Use this to:
          <ul>
            <li>Create helpers (e.g. <code>ModuleHelper</code>).</li>
            <li>Subscribe to events with <code>api.subscribeToEvent(...)</code>.</li>
            <li>Start any background schedulers or workers.</li>
          </ul>
        </li>
        <li>
          <code>void shutdown()</code> – Called during graceful shutdown. Clean up threads,
          close HTTP clients, flush buffers, etc.
        </li>
      </ul>

      <h3>CoreSystemApi</h3>
      <p>The main gateway between your module and the rest of the SOAR framework.</p>
      <pre className="code-block">
{`public interface CoreSystemApi {
    void publishEvent(Event<?> event);
    void subscribeToEvent(String eventType, java.util.function.Consumer<Event<?>> listener);
}`}
      </pre>
      <ul>
        <li>
          <code>publishEvent(Event&lt;?&gt; event)</code>
          <ul>
            <li><strong>Purpose:</strong> Send an event into the framework so that other modules or the
              Workflow Engine can act on it.</li>
            <li><strong>Typical usage:</strong> wrap a payload in <code>Event.of("event.type", payload)</code>
              and call <code>api.publishEvent(...)</code>.</li>
            <li><strong>Errors:</strong> In case of transient failures (e.g. broker unavailable), you
              should normally catch exceptions and log via <code>ModuleHelper</code>, possibly with
              retry logic.</li>
          </ul>
        </li>
        <li>
          <code>subscribeToEvent(String eventType, Consumer&lt;Event&lt;?&gt;&gt; listener)</code>
          <ul>
            <li><strong>Purpose:</strong> Register a callback for events of a particular type or
              pattern (e.g. <code>"alerts.host.*"</code>).</li>
            <li><strong>Callback:</strong> The listener receives an <code>Event&lt;?&gt;</code> object.
              You cast <code>event.getData()</code> to the expected payload type.</li>
            <li><strong>Patterns:</strong> The framework supports AMQP-style routing patterns such as
              <code>alerts.*</code>, <code>alerts.host.*</code>, <code>*.wazuh</code> as described in the
              SDK documentation.</li>
          </ul>
        </li>
      </ul>

      <h3>Event&lt;T&gt;</h3>
      <p>
        A generic envelope for all messages flowing through the system.
      </p>
      <pre className="code-block">
{`public final class Event<T> {
    private final String id;        // UUID string
    private final java.time.Instant timestamp;
    private final String type;      // e.g. "alerts.host.wazuh"
    private final T data;           // payload

    public static <T> Event<T> of(String type, T data) {
        return new Event<>(
            java.util.UUID.randomUUID().toString(),
            java.time.Instant.now(),
            type,
            data
        );
    }
}`}
      </pre>
      <ul>
        <li>
          <strong><code>id</code></strong> – Unique identifier for tracing and correlation.
        </li>
        <li>
          <strong><code>timestamp</code></strong> – Creation time in UTC.
        </li>
        <li>
          <strong><code>type</code></strong> – Routing key / event type, such as
          <code>"alerts.host.wazuh"</code> or <code>"enrichment.result.ip"</code>.
        </li>
        <li>
          <strong><code>data</code></strong> – Strongly-typed payload object.
        </li>
      </ul>

      <h3>Helper &amp; Data Classes (Overview)</h3>
      <p>
        The SDK also defines helper and payload classes used in the examples:
      </p>
      <ul>
        <li><code>ModuleHelper</code> – Convenience wrapper around logging and common patterns.</li>
        <li><code>HostAlertData</code> – Normalized alert data for host-based alerts.</li>
        <li><code>EnrichmentRequestData</code> – Request for extra context (e.g. by IP or hash).</li>
        <li><code>IpReputationData</code> – Result of an IP enrichment / reputation lookup.</li>
        <li><code>MitigationCommandData</code> – A command to perform some action (block IP, quarantine host).</li>
        <li><code>MitigationAction</code> – Enum describing supported mitigation actions.</li>
      </ul>

      <p style={{ marginTop: '16px' }}>
        For each of these types, you can follow the patterns shown in the SDK Detailed Context
        document and the Wazuh/OpenDaylight examples to understand which fields are required
        and how they are typically used.
      </p>
    </div>
  );
};

export default SdkCoreApi;
