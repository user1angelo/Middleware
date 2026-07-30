import React from 'react';

const SdkTroubleshooting = () => {
  return (
    <div className="card">
      <h2 className="card-title">Common Mistakes &amp; Troubleshooting</h2>
      <p>
        Everything on this page is a real thing that actually happened during development of this
        SDK - not a hypothetical "best practice" list. Each item links back to where it's analyzed
        in more depth if you want the full story.
      </p>

      <h3>Common Mistakes (Gotchas)</h3>
      <ol>
        <li>
          <strong>Adding a new embedded module is not self-contained.</strong> If your module
          implements <code>PluggableModule</code> (the embedded pattern), it must be added to
          <code>SdkModuleHost.initializeModules()</code>'s hardcoded class list - a shared file you
          didn't write, that every other embedded module also depends on. This is <em>not</em> true
          of the standalone pattern (own <code>main()</code>, no <code>PluggableModule</code>) -
          adding one of those requires editing zero shared files. <strong>If you're not sure which
          pattern to use, default to standalone</strong> unless you specifically need in-process
          access to the shared event bus. See the Patterns tab's "why choose this" comparison, and
          <code>SDK_USABILITY_AUDIT.md</code>'s Premature Commitment / API Viscosity sections for
          the full reasoning.
        </li>
        <li>
          <strong>Workflow YAML files are not discovered recursively.</strong>
          <code>WorkflowLoader</code> only scans the exact directory it's pointed at - a workflow
          file placed in a subfolder (e.g. <code>workflows/ransomware/notifications/</code> instead
          of <code>workflows/ransomware/</code>) is silently invisible. It won't error; it just
          never matches anything. Always verify a new workflow with <code>WorkflowDryRunTool</code>
          (Core API Reference tab) before assuming it's wired up correctly.
        </li>
        <li>
          <strong>Classpath separator differs by OS.</strong> Use <code>:</code> on Linux/Ubuntu,
          <code>;</code> on Windows. The root <code>README.md</code> documents both forms with a
          comment - don't copy a classpath string from it verbatim without checking which OS it's
          written for.
        </li>
        <li>
          <strong>Embedded modules share one event-subscription map.</strong>
          <code>CoreSystemApi</code>'s listener map is shared across every embedded module running
          in the same process. Don't assume your module's registered capabilities are isolated from
          another embedded module's - this was a real, previously-shipped bug (fixed by snapshotting
          capabilities before/after each module's own <code>initialize()</code> call), and the
          underlying shared-state design that caused it is still there.
        </li>
        <li>
          <strong><code>MitigationCommandData.additionalParameters</code> is typed, not a raw
          string.</strong> It's a <code>MitigationParameters</code> object (see Core API
          Reference). If you see or write code treating it as a plain <code>String</code> you're
          looking at stale example code - that raw-string design was replaced specifically because
          it was undocumented and only the receiving module knew its shape.
        </li>
        <li>
          <strong>Workflow conditions are matched by a substring scanner, not a real parser.</strong>
          <code>WorkflowMatcher</code> looks for known field names/operators as substrings of the
          condition text - it is not a full expression evaluator. Unusual condition syntax may
          silently fail to match rather than raising an error. Always test a new condition with
          <code>WorkflowDryRunTool</code> before trusting it.
        </li>
        <li>
          <strong>A <code>.jar</code> in <code>user-defined-modules/</code> is a dashboard detail,
          not what actually runs your module.</strong> The webapp's Modules page discovers modules
          by scanning for <code>.jar</code> files in that directory - but that scan has nothing to
          do with how modules actually load. Embedded modules load via
          <code>SdkModuleHost</code>'s hardcoded class list; standalone modules run directly via
          <code>java -cp target/classes ... com.nis1.thesis.udm.YourModule</code>. Packaging a jar
          is only needed if you want your module to show up on the Modules page - it's not a
          prerequisite for the module to actually work.
        </li>
      </ol>

      <h3>Troubleshooting / FAQ</h3>
      <ul>
        <li>
          <strong><code>mvn: command not found</code> / <code>java: command not found</code></strong>
          - not installed or not on <code>PATH</code>. On Ubuntu:
          <code>sudo apt install openjdk-17-jdk maven -y</code>, then open a new terminal.
        </li>
        <li>
          <strong><code>javac</code> prints nothing</strong> - that's success, not a hang.
          <code>javac</code> is silent when it works; it only prints on error or warning.
        </li>
        <li>
          <strong>A Java process exits immediately with a connection error</strong> - RabbitMQ
          isn't running or isn't reachable on <code>localhost:5672</code>. Check with
          <code>nc -zv localhost 5672</code>.
        </li>
        <li>
          <strong>"Failed to load modules from database" / "Failed to save module to database"</strong>
          - expected and harmless if PostgreSQL isn't running. It's used only for optional
          persistence (real-time capability display on the Modules page); everything else keeps
          working over RabbitMQ without it.
        </li>
        <li>
          <strong><code>mvn test</code> fails with a specific test name</strong> - scroll up from
          the failure to find the actual assertion that broke; test names describe what they check
          (e.g. <code>alertMissingRequiredFieldsIsLoggedAndDropped</code>).
        </li>
        <li>
          <strong>Port 8481 (Maltrail) or 8482 (Sysmon) already in use</strong> - check what's using
          it with <code>sudo ss -tulpn | grep 8481</code> (or <code>8482</code>); either free it or
          change the port in the module's <code>config/*.properties</code> file.
        </li>
        <li>
          <strong>Fail2ban events never arrive during a simulated test</strong> - the module tails
          whatever path is set in <code>fail2ban-module.properties</code>
          (<code>fail2ban.log_path</code>, default <code>simulated_logs/fail2ban.log</code>,
          relative to wherever the module process was launched from). Make sure your test script is
          writing to that exact same path.
        </li>
        <li>
          <strong>Your new module doesn't appear on the Modules page</strong> - that page only lists
          modules with a <code>.jar</code> file directly in <code>user-defined-modules/</code> (see
          the gotcha above) plus whatever's in the <code>registered_modules</code> Postgres table.
          Compiling and running your module doesn't require this; it's cosmetic dashboard
          visibility only.
        </li>
      </ul>

      <p style={{ marginTop: '16px' }}>
        For the full, evidence-based usability evaluation behind every item on this page, see
        <code>SDK_USABILITY_AUDIT.md</code> at the repository root. For step-by-step commands to
        verify any of this yourself, see <code>TESTING_GUIDE.md</code>.
      </p>
    </div>
  );
};

export default SdkTroubleshooting;
