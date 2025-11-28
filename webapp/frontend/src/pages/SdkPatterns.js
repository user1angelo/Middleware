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
        EnrichmentRequestData request = new EnrichmentRequestData(alert.getSourceIp());
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

      <h3>4. Beginner Workflow: Your First End-to-End Module</h3>
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
        For deeper dives, consult the full SDK Detailed Context document and the real
        <code>WazuhModule</code> and <code>OpenDaylightModule</code> implementations in the
        repository. The patterns above are designed so even a beginner can orient
        themselves and build useful modules step-by-step.
      </p>
    </div>
  );
};

export default SdkPatterns;
