package com.nis1.thesis.eval;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.MitigationAction;
import com.nis1.thesis.sdk.MitigationCommandData;
import com.nis1.thesis.sdk.ModuleHelper;
import com.nis1.thesis.sdk.PluggableModule;

/**
 * ============================================================================================
 *  SDK USABILITY EVALUATION - SKELETON 2 of 2 - EMBEDDED PATTERN
 * ============================================================================================
 *
 * WHAT THIS IS: FooGuard (the same fictional tool as skeleton 1) also ships a local enforcement
 * agent - it can block an IP address at the host firewall when told to. This skeleton wires that
 * capability into the system the way OpenDaylightModule.java does for SDN isolation: by
 * implementing PluggableModule and reacting to mitigation commands, instead of running its own
 * process like skeleton 1 does.
 *
 * YOUR TASK: 4 TODOs below. Read
 *   user-defined-modules/src/main/java/com/nis1/thesis/udm/OpenDaylightModule.java
 * side by side with this file - it is the real, live module this skeleton is modeled on
 * (specifically its getName()/initialize()/onMitigationCommand()/shutdown() methods; you can
 * ignore its topology-discovery and policy-install logic, which don't have an equivalent here).
 *
 * IMPORTANT - READ BEFORE YOU START: unlike skeleton 1, filling in this class is *not* enough to
 * make FooGuard's enforcement actually run inside the live system. A real embedded module must
 * also be added to two hardcoded chokepoints in
 * ModuleRegistryLifecycleManager/src/main/java/com/yourorg/registry/SdkModuleHost.java:
 *
 *   1. initializeModules() - a fixed list of module class names to instantiate at startup.
 *   2. dispatch() / mapMessageTypeToEventType() - an if/else chain deciding how to deserialize
 *      each incoming command type into the right payload object.
 *
 * This skeleton deliberately does NOT ask you to make those edits - that file's dispatch logic is
 * what OpenDaylightModule's live SDN mitigation routing depends on today, and editing it casually
 * is exactly the kind of change SDK_USABILITY_AUDIT.md (Part 3.5) documents as high-risk and
 * deliberately deferred. Instead: open SdkModuleHost.java, read those two methods, and form your
 * own estimate of how much work and risk it would take to add FooGuardMitigationSkeleton there
 * for real. That reading exercise - not code you write - is what should inform your rating of
 * Premature Commitment, Work-Step Unit, and API Viscosity for this pattern. Skeleton 1's pattern
 * required zero such edits anywhere; that asymmetry is the whole point of comparing the two.
 *
 * WHEN YOU'RE DONE: `mvn -pl sdk-usability-evaluation compile` should succeed with no errors.
 * ============================================================================================
 */
public class FooGuardMitigationSkeleton implements PluggableModule {

    private CoreSystemApi api;
    private ModuleHelper helper;
    private FooGuardFirewallClient firewallClient;

    private final String moduleId = "fooguard_enforcer_eval";
    private final String moduleName = "FooGuard Enforcer (Evaluation Skeleton)";

    private volatile boolean running = false;

    /**
     * TODO-1 [Abstraction Level]: return a stable, human-readable name for this module, the way
     * OpenDaylightModule.getName() returns its moduleName field. Used for logging/diagnostics
     * throughout the system - see PluggableModule.java's Javadoc for the contract.
     */
    @Override
    public String getName() {
        // TODO-1: implement me
        throw new UnsupportedOperationException("TODO-1: getName not implemented");
    }

    /**
     * TODO-2 [Working Framework / Learning Style]: this is the module's startup hook - it's
     * called exactly once before the module is expected to be operational. Fill in:
     *   1. Store `api` and build a ModuleHelper from it (see OpenDaylightModule.initialize(),
     *      first three lines).
     *   2. Construct firewallClient = new FooGuardFirewallClient(helper, getName()) (the stub
     *      class at the bottom of this file - it simulates enforcement so this compiles and runs
     *      without a real firewall, the same way OpenDaylightClient's RESTCONF calls are stubbed
     *      for simulation/demo per user-defined-modules/README.md).
     *   3. Set running = true.
     *   4. Subscribe to the event type this module should react to via
     *      api.subscribeToEvent(eventType, listener) - CoreSystemApi.java documents the method.
     *      Use "INITIATE_MITIGATION" (the same event type OpenDaylightModule subscribes to) and
     *      point it at onMitigationCommand below, e.g. api.subscribeToEvent("INITIATE_MITIGATION",
     *      this::onMitigationCommand).
     *   5. Log that you're up, via helper.log(getName(), "INFO", ...) - see the Penetrability note
     *      in the class-level comment above: every module in this repo logs heavily specifically
     *      so its behavior is observable from console output alone.
     */
    @Override
    public void initialize(CoreSystemApi api) {
        // TODO-2: implement me
        throw new UnsupportedOperationException("TODO-2: initialize not implemented");
    }

    /**
     * TODO-4 [minor - deterministic cleanup]: flip running to false and log the shutdown, the way
     * OpenDaylightModule.shutdown() does in two lines.
     */
    @Override
    public void shutdown() {
        // TODO-4: implement me
        throw new UnsupportedOperationException("TODO-4: shutdown not implemented");
    }

    /**
     * TODO-3 [Domain Correspondence / Role Expressiveness]: this is the reactive handler you wired
     * up in TODO-2. Follow OpenDaylightModule.onMitigationCommand(Event<?>) as your model:
     *   1. Bail out early if !running.
     *   2. Get event.getData() and check `instanceof MitigationCommandData` before casting -
     *      CoreSystemApi's shared subscriber map is untyped (Consumer<Event<?>>), so this cast
     *      check is not optional boilerplate, it's the actual type safety boundary.
     *   3. Pull targetHost (String, an IP in this scenario) and action (MitigationAction) off the
     *      command.
     *   4. Only act if action == MitigationAction.BLOCK_IP - FooGuard's enforcer only knows how to
     *      block an IP, unlike OpenDaylightModule which handles several MitigationAction values.
     *      For any other action, log at "INFO" that this module doesn't handle it and return -
     *      decide for yourself whether silently ignoring vs. logging is the more honest choice
     *      here (worth noting when you rate Role Expressiveness).
     *   5. Call firewallClient.blockIp(targetHost) and log the result via helper.log(...).
     */
    private void onMitigationCommand(Event<?> event) {
        // TODO-3: implement me
        throw new UnsupportedOperationException("TODO-3: onMitigationCommand not implemented");
    }

    /**
     * Stub enforcement client - simulates blocking an IP at a local firewall. Given, not part of
     * the exercise. Mirrors OpenDaylightClient's own "stubbed for simulation/demo" RESTCONF calls
     * (see user-defined-modules/README.md) - the point of this skeleton is the SDK's
     * PluggableModule/CoreSystemApi/Event contract, not building a real firewall integration.
     */
    static class FooGuardFirewallClient {
        private final ModuleHelper helper;
        private final String moduleName;

        FooGuardFirewallClient(ModuleHelper helper, String moduleName) {
            this.helper = helper;
            this.moduleName = moduleName;
        }

        boolean blockIp(String ip) {
            helper.log(moduleName, "INFO", "[SIMULATED] Would block IP at host firewall: " + ip);
            return true;
        }
    }
}
