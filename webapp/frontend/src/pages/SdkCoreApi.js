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
              prefix (e.g. <code>"alerts.host.*"</code>).</li>
            <li><strong>Callback:</strong> The listener receives an <code>Event&lt;?&gt;</code> object.
              You cast <code>event.getData()</code> to the expected payload type.</li>
            <li><strong>Patterns (be precise here):</strong> only two matching rules exist -
              an <strong>exact</strong> match against <code>eventType</code>, or a
              single-level <strong>prefix</strong> wildcard where <code>eventType</code> ends in
              <code>".*"</code> (e.g. <code>"alerts.host.*"</code> matches
              <code>"alerts.host.wazuh"</code>). This is <em>not</em> full AMQP topic routing -
              there is no multi-segment <code>#</code> wildcard and no suffix pattern like
              <code>"*.wazuh"</code>. Subscribing to that literal string would only ever match an
              event whose type is exactly <code>"*.wazuh"</code>, which nothing publishes.</li>
            <li><strong>Note:</strong> this map of subscriptions is shared across every embedded
              module in the same process - see the audit doc linked below for a real bug this
              caused with per-module capability registration.</li>
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

      <h3>ModuleHelper</h3>
      <p>
        A convenience wrapper that hides the <code>Event.of(...)</code> + <code>publishEvent(...)</code>
        boilerplate for a handful of common message types. Construct one with
        <code>new ModuleHelper(api)</code> inside <code>initialize(CoreSystemApi api)</code>.
      </p>
      <ul>
        <li>
          <code>publishHostAlert(String sourceIp, String description, String severity)</code> –
          builds a <code>HostAlertData</code> and publishes it as a <code>"HOST_ALERT"</code> event.
        </li>
        <li>
          <code>publishNidsAlert(String sourceIp, String destinationIp, String signature, String severity)</code> –
          builds a <code>NidsAlertData</code> and publishes it as a <code>"NIDS_ALERT"</code> event.
        </li>
        <li>
          <code>publishIpReputation(String ipAddress, boolean isMalicious, String source)</code> –
          builds an <code>IpReputationData</code> and publishes it as
          <code>"IP_REPUTATION_" + source.toUpperCase()</code>.
        </li>
        <li>
          <code>publishMitigationCommand(String targetHost, MitigationAction action, String justification)</code> –
          builds a <code>MitigationCommandData</code> and publishes it as an
          <code>"INITIATE_MITIGATION"</code> event - this is what <code>OpenDaylightModule</code>
          listens for.
        </li>
        <li>
          <code>requestIpEnrichment(String ipAddress)</code> – builds an
          <code>EnrichmentRequestData</code> (with <code>enrichmentType</code> defaulted to
          <code>"IP_REPUTATION"</code>) and publishes it as <code>"ENRICHMENT_REQUEST_IP"</code>.
        </li>
        <li>
          <code>log(String moduleName, String message)</code> and
          <code>log(String moduleName, String level, String message)</code> – prefixed console
          logging, e.g. <code>[MyModule][INFO] message</code>.
        </li>
      </ul>

      <h3>Payload / Data Classes</h3>
      <p>
        These are the concrete payload types <code>ModuleHelper</code> builds for you. You can
        also construct and publish them directly with <code>Event.of(...)</code> if you need a
        type <code>ModuleHelper</code> doesn't wrap.
      </p>

      <table className="data-table" style={{ marginBottom: '16px' }}>
        <thead>
          <tr><th>Class</th><th>Fields</th><th>Notes</th></tr>
        </thead>
        <tbody>
          <tr>
            <td><code>HostAlertData</code></td>
            <td><code>sourceIp</code>, <code>description</code>, <code>severity</code> (all <code>String</code>)</td>
            <td>Minimal - no timestamp or hostname field of its own.</td>
          </tr>
          <tr>
            <td><code>NidsAlertData</code></td>
            <td><code>sourceIp</code>, <code>destinationIp</code>, <code>sourcePort</code>/<code>destinationPort</code> (<code>Integer</code>), <code>protocol</code>, <code>signature</code>, <code>signatureSeverity</code>, <code>category</code>, <code>hostTag</code></td>
            <td>No <code>@SerializedName</code> annotations - JSON keys are raw camelCase, unlike the snake_case convention used by <code>SuricataAlertData</code>/<code>MaltrailAlertData</code> in the user-defined modules.</td>
          </tr>
          <tr>
            <td><code>IpReputationData</code></td>
            <td><code>ipAddress</code>, <code>isMalicious</code> (<code>boolean</code>), <code>source</code>, <code>category</code>, <code>confidenceScore</code> (<code>Integer</code>)</td>
            <td>Three constructors (no-arg, 3-arg basic, 5-arg full).</td>
          </tr>
          <tr>
            <td><code>EnrichmentRequestData</code></td>
            <td><code>ipAddress</code>, <code>domain</code>, <code>fileHash</code>, <code>enrichmentType</code>, <code>requestId</code></td>
            <td>Built via named static factories - <code>EnrichmentRequestData.forIpAddress(ip)</code> or <code>EnrichmentRequestData.forRequest(enrichmentType, requestId)</code> - rather than overloaded constructors, so the call site itself says which fields get set (a previous pair of same-shaped constructors here was ambiguous at the call site; see <code>SDK_USABILITY_AUDIT.md</code>).</td>
          </tr>
          <tr>
            <td><code>MitigationCommandData</code></td>
            <td><code>targetHost</code>, <code>action</code> (<code>MitigationAction</code>), <code>justification</code>, <code>workflowInstanceId</code>, <code>priority</code> (<code>Integer</code>), <code>additionalParameters</code> (<code>MitigationParameters</code>)</td>
            <td><code>additionalParameters</code> is now a typed <code>MitigationParameters</code> object, not a raw string - see the row below. Previously it was an undocumented JSON-in-a-string whose structure was only knowable from whichever module ended up parsing it; see <code>SDK_USABILITY_AUDIT.md</code>'s Role Expressiveness dimension.</td>
          </tr>
          <tr>
            <td><code>MitigationParameters</code></td>
            <td><code>eventType</code>, <code>messageType</code>, <code>macAddress</code>, <code>mitigationId</code>, <code>rollbackScope</code>, <code>rollbackRequestSource</code>, <code>rollbackReason</code>, <code>severity</code> (all <code>String</code>), <code>quarantinePolicy</code> (<code>QuarantinePolicy</code>), <code>telemetry</code>/<code>lifecycle</code> (<code>Map&lt;String,Object&gt;</code>), plus <code>getExtra(key)</code>/<code>putExtra(key, value)</code> for anything not covered by a named field</td>
            <td>Nested <code>QuarantinePolicy</code> has <code>mode</code>, <code>containArp</code>, <code>containDhcp</code> - the latter two are boxed <code>Boolean</code>, not primitive <code>boolean</code>, so <code>null</code> means "unspecified, use the module's default" rather than silently meaning <code>false</code>.</td>
          </tr>
          <tr>
            <td><code>MitigationAction</code> (enum)</td>
            <td><code>QUARANTINE</code>, <code>BLOCK_IP</code>, <code>RATE_LIMIT</code>, <code>REDIRECT_TRAFFIC</code>, <code>ISOLATE_VLAN</code>, <code>ALERT_ONLY</code>, <code>DISABLE_USER</code>, <code>KILL_PROCESS</code></td>
            <td>A closed set - adding a new mitigation action requires changing the SDK itself, not just a module.</td>
          </tr>
        </tbody>
      </table>

      <h3>Built-in Observability &amp; Testing Tools</h3>
      <p>
        Two tools ship with the SDK that most module authors will never call directly, but that
        matter once you're trying to measure or debug a pipeline built on it.
      </p>
      <ul>
        <li>
          <code>com.nis1.thesis.sdk.telemetry.StageTimer</code> - automatic, always-on latency
          instrumentation. You don't call this from your own module; it's already wired into the
          core pipeline (<code>WorkflowQueueListener</code>, <code>WorkflowExecutor</code>,
          <code>CommandRoutingListener</code>) and times every alert across 5 named stages:
          <code>consume_deserialize</code>, <code>workflow_load</code>, <code>policy_match</code>,
          <code>command_dispatch</code>, <code>registry_route_dispatch</code>. Each completed
          stage appends one row to a CSV
          (<code>benchmark_output/benchmark_run_&lt;date&gt;.csv</code>):
          <pre className="code-block" style={{ marginTop: '8px', marginBottom: '8px' }}>
{`traceId,stage,startTime,endTime,durationMs
alert-1,consume_deserialize,2026-07-30 14:00:01.000,2026-07-30 14:00:01.005,5`}
          </pre>
          <code>startTime</code>/<code>endTime</code> are human-readable timestamps
          (<code>yyyy-MM-dd HH:mm:ss.SSS</code>), not raw epoch milliseconds, so a row can be read
          directly without converting it first; <code>durationMs</code> stays a plain integer
          since it's what gets averaged/percentiled. Run
          <code>python3 scripts/summarize_benchmark.py &lt;benchmark_output-dir&gt;</code> to get
          per-stage and end-to-end latency statistics (mean/median/p95/max) across every alert
          that passed through.
        </li>
        <li>
          <code>WorkflowDryRunTool</code> (in <code>WorkflowEngine/</code>) - the tool you *do* run
          yourself: checks whether a workflow YAML actually matches a given alert JSON, with no
          RabbitMQ, ModuleRegistry, or live module needed.
          <pre className="code-block" style={{ marginTop: '8px', marginBottom: '8px' }}>
{`java -cp "lib/*:target/classes" WorkflowDryRunTool workflows/ransomware sample_alerts/maltrail_ransomware_alert.json`}
          </pre>
          Prints which workflows matched (or a hint about what to check if none did). Replaces
          what used to be a throwaway <code>.java</code> file reinvented from scratch each time a
          new workflow needed checking - see <code>TESTING_GUIDE.md</code>'s Tier 2 and
          <code>SDK_USABILITY_AUDIT.md</code>'s Progressive Evaluation dimension.
        </li>
      </ul>

      <p style={{ marginTop: '16px' }}>
        For the full, evidence-based usability evaluation of this API (what it does well, what it
        doesn't, and why), see <code>SDK_USABILITY_AUDIT.md</code> at the repository root - it
        covers every class on this page plus the standalone-module pattern described on the
        Patterns tab, using the Cognitive Dimensions framework.
      </p>
    </div>
  );
};

export default SdkCoreApi;
