import React from 'react';

const SdkOverview = () => {
  return (
    <div className="card">
      <h2 className="card-title">What is the SOAR SDK?</h2>
      <p>
        The SOAR SDK lets you build <strong>user-defined modules</strong> that plug into the
        Middleware ecosystem (Module Registry, Workflow Engine, and the Message Bus).
        Using a small set of Java interfaces, you can listen for events (alerts,
        enrichment requests, mitigation commands) and publish your own events back
        into the system.
      </p>

      <h3>Big Picture</h3>
      <p>
        At a high level, your module is just a Java class that implements
        <code>PluggableModule</code>. The framework loads it, calls
        <code>initialize(CoreSystemApi api)</code>, and from there you can subscribe
        to events and publish new ones.
      </p>

      <pre className="code-block">
{`public class MyFirstModule implements PluggableModule {
    private CoreSystemApi api;
    private ModuleHelper helper;

    @Override
    public String getName() {
        return "My First SDK Module";
    }

    @Override
    public void initialize(CoreSystemApi api) {
        this.api = api;
        this.helper = new ModuleHelper(api);

        helper.log(getName(), "INFO", "Module initialized");

        // Listen for events
        api.subscribeToEvent("alerts.host.*", this::onHostAlert);
    }

    private void onHostAlert(Event<?> event) {
        HostAlertData alert = (HostAlertData) event.getData();
        helper.log(getName(), "INFO", "Got alert for host " + alert.getHostId());
    }

    @Override
    public void shutdown() {
        helper.log(getName(), "INFO", "Module shutting down");
    }
}`}
      </pre>

      <h3>How the SDK Fits into the Middleware</h3>
      <ul>
        <li><strong>ModuleRegistry &amp; Lifecycle Manager</strong> discovers your JAR and loads your module.</li>
        <li><strong>CoreSystemApi</strong> is your gateway to the message bus (publish/subscribe).</li>
        <li><strong>Event&lt;T&gt;</strong> wraps typed payloads that move between modules and workflows.</li>
        <li><strong>Workflow Engine</strong> drives higher-level logic by emitting events your module can react to.</li>
      </ul>

      <h2>Getting Started (Beginner Friendly)</h2>
      <ol>
        <li>
          <strong>Pick a use case.</strong> For example: "When a high severity alert appears,
          call an external API and publish an enrichment result." 
        </li>
        <li>
          <strong>Create a new <code>*Module.java</code> class</strong> under
          <code>com.nis1.thesis.udm</code> that implements <code>PluggableModule</code>.
        </li>
        <li>
          <strong>Use <code>CoreSystemApi</code> to subscribe</strong> to the events you care about,
          such as <code>alerts.host.wazuh</code> or <code>enrichment.request.ip</code>.
        </li>
        <li>
          <strong>Define a payload class</strong> that represents the data you want to send
          (e.g. <code>IpReputationData</code> or <code>WazuhAlertPayload</code>).
        </li>
        <li>
          <strong>Publish events</strong> back into the system using
          <code>Event.of("event.type", payload)</code> and <code>api.publishEvent(...)</code>.
        </li>
        <li>
          <strong>Package your module as a JAR</strong> and drop it in the directory that the
          Lifecycle Manager scans for plugins (typically <code>user-defined-modules/</code>).
        </li>
      </ol>

      <h3>Where to Go Next</h3>
      <ul>
        <li>
          <strong>Core API Reference:</strong> Detailed docs for <code>PluggableModule</code>,
          <code>CoreSystemApi</code>, <code>Event&lt;T&gt;</code>, and helper/data classes.
        </li>
        <li>
          <strong>Patterns &amp; Examples:</strong> End-to-end examples like the Wazuh module,
          mitigation flows, and enrichment patterns.
        </li>
        <li>
          <strong>SDK Detailed Context:</strong> The original full technical document that
          explains architecture, message formats, and advanced patterns.
        </li>
      </ul>
    </div>
  );
};

export default SdkOverview;
