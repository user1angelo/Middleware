package com.nis1.thesis.udm;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.ModuleHelper;
import com.nis1.thesis.sdk.PluggableModule;

import org.json.JSONObject;

import java.time.Instant;

/**
 * NotificationModule - Delivers workflow-triggered notifications
 *
 * Unlike SuricataModule/MaltrailModule/Fail2banModule/SysmonModule (standalone processes),
 * this module uses the OTHER SDK integration pattern: it's an embedded PluggableModule,
 * loaded in-process by SdkModuleHost, exactly like OpenDaylightModule. Where
 * OpenDaylightModule reacts to INITIATE_MITIGATION/REMOVE_MITIGATION by talking to an SDN
 * controller, this module reacts to SEND_NOTIFICATION by printing a clearly-formatted
 * console notification - demonstrating the embedded pattern works identically for a
 * completely different kind of action.
 *
 * Requires two small core-file changes to wire up (unlike the standalone modules, which
 * needed zero): SdkModuleHost.initializeModules() must list this class, and
 * SdkModuleHost.dispatch() must recognize the SEND_NOTIFICATION event type - both are
 * necessary consequences of the embedded pattern's design (see SDK_USABILITY_AUDIT.md,
 * Work-Step Unit / API Viscosity dimensions).
 */
public class NotificationModule implements PluggableModule {

    private CoreSystemApi api;
    private ModuleHelper helper;
    private final String moduleName = "Notification Module";
    private volatile boolean running = false;

    @Override
    public String getName() {
        return moduleName;
    }

    @Override
    public void initialize(CoreSystemApi api) {
        this.api = api;
        this.helper = new ModuleHelper(api);
        this.running = true;

        helper.log(getName(), "INFO", "Initializing NotificationModule");

        api.subscribeToEvent("SEND_NOTIFICATION", this::onNotify);

        helper.log(getName(), "INFO", "Subscribed to SEND_NOTIFICATION");
    }

    @Override
    public void shutdown() {
        running = false;
        helper.log(getName(), "INFO", "Shutting down NotificationModule");
    }

    /**
     * Handles a SEND_NOTIFICATION event by printing a clearly-formatted console
     * notification block. This is intentionally the simplest possible action - the point
     * is demonstrating the embedded pattern works for a non-SDN action, not building a
     * notification delivery system.
     */
    private void onNotify(Event<?> event) {
        if (!running) {
            return;
        }

        Object data = event.getData();
        JSONObject notification = (data instanceof JSONObject) ? (JSONObject) data : new JSONObject();

        String title = notification.optString("title", "Notification");
        String message = notification.optString("message", "(no message provided)");
        String severity = notification.optString("severity", "info");
        String source = notification.optString("source", notification.optString("source_ip", "unknown"));

        System.out.println("\n🔔━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━🔔");
        System.out.println("🔔 NOTIFICATION [" + severity.toUpperCase() + "]");
        System.out.println("🔔 " + title);
        System.out.println("🔔 " + message);
        System.out.println("🔔 Source: " + source);
        System.out.println("🔔 Time: " + Instant.now());
        System.out.println("🔔 Event ID: " + event.getId());
        System.out.println("🔔━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━🔔\n");
    }
}
