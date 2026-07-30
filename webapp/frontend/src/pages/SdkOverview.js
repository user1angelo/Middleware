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
          <strong>Choose a pattern.</strong> Embedded (implements <code>PluggableModule</code>,
          shown above) requires editing a shared file to register your module - standalone (own
          <code>main()</code>) doesn't. <strong>If you're unsure, start with standalone</strong> -
          see the walkthrough below and the Patterns tab's full comparison.
        </li>
        <li>
          <strong>Use <code>CoreSystemApi</code> to subscribe</strong> (embedded) or your own
          RabbitMQ channel (standalone) for the events you care about, such as
          <code>alerts.network.maltrail</code> or <code>enrichment.request.ip</code>.
        </li>
        <li>
          <strong>Define a payload class</strong> that represents the data you want to send
          (e.g. <code>IpReputationData</code> or <code>MaltrailAlertData</code>), and build your
          alert envelope with <code>AlertEnvelopeBuilder</code> (Core API Reference tab).
        </li>
        <li>
          <strong>Publish events</strong> back into the system using
          <code>Event.of("event.type", payload)</code> and <code>api.publishEvent(...)</code>
          (embedded), or <code>channel.basicPublish(...)</code> (standalone).
        </li>
      </ol>

      <h3>Your First Module, End to End</h3>
      <p>
        Rather than a hypothetical example, this walks through a real module that's already built,
        tested, and running in this repo - <code>Fail2banModule</code> (the simplest of the four
        standalone modules: it tails a log file and publishes an alert on a match). Every command
        below has actually been run against this exact codebase.
      </p>
      <ol>
        <li>
          <strong>Read the real source first.</strong>
          <code>user-defined-modules/src/main/java/com/nis1/thesis/udm/Fail2banModule.java</code> -
          notice it has its own <code>main()</code>, its own RabbitMQ connection, and no
          <code>PluggableModule</code> in sight. That's the standalone pattern.
        </li>
        <li>
          <strong>Compile and test it</strong> - Maven already knows about every dependency, so no
          manual classpath is needed for this step:
          <pre className="code-block" style={{ marginTop: '8px', marginBottom: '8px' }}>
{`cd $REPO
mvn compile
mvn test`}
          </pre>
        </li>
        <li>
          <strong>Run it for real</strong> (needs RabbitMQ - see <code>TESTING_GUIDE.md</code> Tier
          3 to start one). This runs directly against the compiled classes - no jar required:
          <pre className="code-block" style={{ marginTop: '8px', marginBottom: '8px' }}>
{`cd $REPO/user-defined-modules
java -cp "target/classes:../ModuleRegistryLifecycleManager/lib/*" com.nis1.thesis.udm.Fail2banModule`}
          </pre>
        </li>
        <li>
          <strong>(Optional) Make it show up on the Modules page.</strong> This is a separate,
          cosmetic step - the Modules page only lists modules with a <code>.jar</code> sitting
          directly in <code>user-defined-modules/</code>, which has nothing to do with whether your
          module actually runs (see the Troubleshooting tab):
          <pre className="code-block" style={{ marginTop: '8px', marginBottom: '8px' }}>
{`cd $REPO/user-defined-modules
javac -cp "../nis-thesis-sdk/target/nis-thesis-sdk-1.0-SNAPSHOT.jar:../ModuleRegistryLifecycleManager/lib/json-20231013.jar:../ModuleRegistryLifecycleManager/lib/gson-2.13.1.jar:../ModuleRegistryLifecycleManager/lib/amqp-client-5.26.0.jar" \\
  -d target/classes_tmp src/main/java/com/nis1/thesis/udm/Fail2banModule.java src/main/java/com/nis1/thesis/udm/Fail2banAlertData.java
jar cf target/fail2ban-module.jar -C target/classes_tmp .
cp target/fail2ban-module.jar .`}
          </pre>
        </li>
      </ol>

      <h3>Where to Go Next</h3>
      <ul>
        <li>
          <strong>Core API Reference:</strong> Detailed docs for <code>PluggableModule</code>,
          <code>CoreSystemApi</code>, <code>Event&lt;T&gt;</code>, and helper/data classes.
        </li>
        <li>
          <strong>Patterns &amp; Examples:</strong> End-to-end examples like the standalone
          Suricata/Maltrail/Fail2ban/Sysmon modules, the embedded OpenDaylight mitigation flow,
          and enrichment patterns.
        </li>
        <li>
          <strong>Common Mistakes &amp; Troubleshooting:</strong> Real gotchas found while building
          this SDK, and fixes for the errors you're most likely to actually hit.
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
