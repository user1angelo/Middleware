import React from 'react';

const SdkPatterns = () => {
  return (
    <div className="card">
      <h2 className="card-title">Patterns &amp; Examples</h2>
      <p>
        This section summarizes common patterns from the SDK Detailed Context document and shows
        how to apply them as a beginner.
      </p>

      <h3>1. Reactive Pattern – Responding to Alerts</h3>
      <p>
        Listen for alerts, do some logic, and optionally trigger enrichment or mitigation.
      </p>
      <pre className="code-block">
{`@Override
public void initialize(CoreSystemApi api) {
    this.api = api;
    this.helper = new ModuleHelper(api);

    // React to any host alert
    api.subscribeToEvent("alerts.host.*", this::handleHostAlert);
}

private void handleHostAlert(Event<?> event) {
    HostAlertData alert = (HostAlertData) event.getData();

    if ("critical".equals(alert.getSeverity())) {
        // Ask another module to enrich this IP
        EnrichmentRequestData request = EnrichmentRequestData.forIpAddress(alert.getSourceIp());
        Event<EnrichmentRequestData> enrichmentEvent =
            Event.of("enrichment.request.ip", request);
        api.publishEvent(enrichmentEvent);
    }
}`}
      </pre>

      <h3>2. Proactive Pattern – Scheduled Tasks</h3>
      <p>
        Run recurring jobs (e.g. periodic scans, posture reports) and publish events with the
        results.
      </p>
      <pre className="code-block">
{`private java.util.concurrent.ScheduledExecutorService scheduler;

@Override
public void initialize(CoreSystemApi api) {
    this.api = api;
    this.helper = new ModuleHelper(api);
    this.scheduler = java.util.concurrent.Executors.newScheduledThreadPool(1);

    scheduler.scheduleAtFixedRate(this::performThreatHunt, 0, 15,
        java.util.concurrent.TimeUnit.MINUTES);
}

private void performThreatHunt() {
    // Your custom logic here
    ThreatHuntResult result = runCustomQueries();
    Event<ThreatHuntResult> event = Event.of("threathunt.result", result);
    api.publishEvent(event);
}`}
      </pre>

      <h3>3. Correlation Pattern – Combining Multiple Events</h3>
      <p>
        Collect related events (e.g. multiple alerts for the same IP) and raise a single
        "correlated" higher-level event.
      </p>
      <pre className="code-block">
{`private final java.util.Map<String, java.util.List<Event<?>>> buffer =
    new java.util.concurrent.ConcurrentHashMap<>();

private void handleAlert(Event<?> event) {
    String ip = extractSourceIp(event);
    buffer.computeIfAbsent(ip, k -> new java.util.ArrayList<>()).add(event);
    analyzeCorrelation(ip);
}

private void analyzeCorrelation(String ip) {
    java.util.List<Event<?>> events = buffer.get(ip);
    if (events != null && events.size() >= 3) {
        CorrelationResult result = performCorrelationAnalysis(events);
        if (result.isHighRisk()) {
            Event<CorrelationResult> corrEvent =
                Event.of("correlation.threat", result);
            api.publishEvent(corrEvent);
        }
    }
}`}
      </pre>

      <h3>4. Standalone Process Pattern - the OTHER integration path</h3>
      <p>
        Everything above assumes an <strong>embedded</strong> module: it implements
        <code>PluggableModule</code> and is loaded in-process by the Module Registry from a fixed
        list. Most of the real modules in this repository (<code>SuricataModule</code>,
        <code>MaltrailModule</code>, <code>Fail2banModule</code>, <code>SysmonModule</code>) use a
        completely different, second pattern instead: a plain Java class with its own
        <code>main()</code>, its own RabbitMQ connection, and its own
        registration/heartbeat/command-listener logic - it does <strong>not</strong> implement
        <code>PluggableModule</code> at all.
      </p>
      <pre className="code-block">
{`public class MyToolModule {
    public static void main(String[] args) {
        loadConfig();  // read config/my-tool-module.properties, fall back to defaults

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        // ...
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        sendRegistration(channel);   // publish a "registration" message to workflow_queue
        startHeartbeats(channel);    // periodic "heartbeat" messages
        startCommandListener(channel); // consume this module's own command queue
        startIngestion(channel);     // tail a log file, or listen on a UDP/HTTP port -
                                      // your choice, the SDK doesn't prescribe one

        // keep the process alive until Ctrl+C
    }

    // Called once per new piece of input (a log line, a UDP packet, ...)
    static void parseAndPublish(String rawInput, Channel channel) {
        JSONObject envelope = AlertEnvelopeBuilder.create()
            .eventType("alerts.network.mytool")
            .sourceModule("MyToolModule")
            .payload(buildPayloadFrom(rawInput))
            .build(); // fills in event_id/timestamp for you

        channel.basicPublish("", "workflow_queue", null, envelope.toString().getBytes());
    }
}`}
      </pre>
      <p><strong>Why choose this over the embedded pattern?</strong></p>
      <ul>
        <li>
          <strong>Zero shared-file changes.</strong> A new standalone module is entirely
          self-contained - nothing in <code>CoreSystemApi</code>, <code>WorkflowMatcher</code>,
          <code>WorkflowEngine</code>, or the Module Registry needs to change. The embedded
          pattern, by contrast, requires editing a hardcoded module list and (for a new event
          type) a hardcoded dispatch method shared with every other embedded module.
        </li>
        <li>
          <strong>Runs as its own process.</strong> Useful when your integration wants its own
          crash isolation, its own restart policy, or needs to keep polling/tailing something
          continuously regardless of what else is happening in the registry process.
        </li>
        <li>
          <strong>Trade-off:</strong> you still write your own RabbitMQ plumbing by hand
          (connection, registration, heartbeats, command listener) - but envelope construction no
          longer has to be reinvented per module. <code>nis-thesis-sdk</code> now ships a shared
          <code>AlertEnvelopeBuilder</code> (fluent, auto-fills <code>event_id</code>/
          <code>timestamp</code>) that all four real standalone modules
          (<code>SuricataModule</code>, <code>MaltrailModule</code>, <code>Fail2banModule</code>,
          <code>SysmonModule</code>) use instead of hand-building the envelope JSON separately -
          see the audit doc below for what this replaced.
        </li>
      </ul>
      <p>
        Use the embedded pattern (sections 1-3 above) when you want to react to events already
        flowing through the system, in-process, with minimal boilerplate. Use the standalone
        pattern when you're integrating an entirely new external tool that produces its own
        stream of data (a log file, a UDP feed, an HTTP webhook) and don't need anything from the
        rest of the system except a place to publish alerts to.
      </p>

      <h3>5. Beginner Workflow: Your First End-to-End Module</h3>
      <ol>
        <li><strong>Start from the template</strong> in the Overview tab (a bare
          <code>PluggableModule</code> implementation).</li>
        <li><strong>Decide which events</strong> you want to listen to (e.g.
          <code>alerts.host.wazuh</code>, <code>enrichment.request.ip</code>).</li>
        <li><strong>Create a payload class</strong> for what you want to publish
          (e.g. <code>IpReputationData</code> or <code>MyCustomFinding</code>).</li>
        <li><strong>Write a small handler method</strong> that casts
          <code>event.getData()</code> to the correct type and does your logic.</li>
        <li><strong>Publish a result event</strong> with <code>Event.of(...)</code> and
          <code>api.publishEvent(...)</code>.</li>
        <li><strong>Package the module as a JAR</strong> and place it in the
          user-defined modules directory so the Lifecycle Manager can load it.</li>
      </ol>

      <p style={{ marginTop: '16px' }}>
        For deeper dives, consult the real <code>OpenDaylightModule</code> (embedded pattern) and
        <code>SuricataModule</code>/<code>MaltrailModule</code>/<code>Fail2banModule</code>/
        <code>SysmonModule</code> (standalone pattern) implementations in
        <code>user-defined-modules/</code>. For an honest, evidence-based assessment of how usable
        this SDK actually is across both patterns - including several real bugs and
        inconsistencies found while building the modules above - see
        <code>SDK_USABILITY_AUDIT.md</code> at the repository root.
      </p>
    </div>
  );
};

export default SdkPatterns;
