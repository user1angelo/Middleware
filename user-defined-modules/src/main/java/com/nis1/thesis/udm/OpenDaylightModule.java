package com.nis1.thesis.udm;

import com.nis1.thesis.sdk.CoreSystemApi;
import com.nis1.thesis.sdk.Event;
import com.nis1.thesis.sdk.MitigationAction;
import com.nis1.thesis.sdk.MitigationCommandData;
import com.nis1.thesis.sdk.ModuleHelper;
import com.nis1.thesis.sdk.PluggableModule;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * OpenDaylightModule - SDK-based pluggable module for SDN mitigation.
 *
 * This implementation is designed to be loaded by the Module Registry & Lifecycle Manager
 * via the SOAR SDK. It no longer connects directly to RabbitMQ or sends registration
 * messages itself. Instead, it relies on the CoreSystemApi to subscribe to mitigation-
 * related events and to publish any future response events.
 *
 * For now, the module provides a **stubbed implementation** that:
 * - Loads OpenDaylight connection settings from a dedicated properties file
 * - Subscribes to INITIATE_MITIGATION events via CoreSystemApi
 * - Logs how it would translate those commands into RESTCONF calls to OpenDaylight
 *
 * Once the RESTCONF details are finalized, the simulate* methods can be replaced with
 * real HTTP calls using the configured base URL and credentials.
 */
public class OpenDaylightModule implements PluggableModule {

    private static final String CONFIG_PATH = "config/opendaylight-module.properties";

    private CoreSystemApi api;
    private ModuleHelper helper;

    // Module identity (override via config)
    private String moduleId = "odl_sdn_01";
    private String moduleName = "OpenDaylight SDN Module";
    @SuppressWarnings("FieldCanBeLocal")
    private String moduleType = "sdn_controller";

    // OpenDaylight RESTCONF settings (override via config)
    private String odlBaseUrl = "http://opendaylight:8181";
    private String odlUsername = "admin";
    private String odlPassword = "admin";

    private volatile boolean running = false;

    @Override
    public String getName() {
        return moduleName;
    }

    @Override
    public void initialize(CoreSystemApi api) {
        this.api = api;
        this.helper = new ModuleHelper(api);

        loadConfig();
        this.running = true;

        helper.log(getName(), "INFO", "Initializing OpenDaylightModule (id=" + moduleId + ")");
        helper.log(getName(), "INFO", "Using OpenDaylight base URL: " + odlBaseUrl);

        // Subscribe to mitigation commands published by the Workflow Engine
        api.subscribeToEvent("INITIATE_MITIGATION", this::onMitigationCommand);

        // Optional future subscription for more SDN-specific flows
        api.subscribeToEvent("SDN_INSTALL_FLOW", this::onSdnInstallFlow);

        helper.log(getName(), "INFO", "Subscriptions registered for INITIATE_MITIGATION and SDN_INSTALL_FLOW");
    }

    @Override
    public void shutdown() {
        running = false;
        if (helper != null) {
            helper.log(getName(), "INFO", "Shutting down OpenDaylightModule");
        }
    }

    /**
     * Handle INITIATE_MITIGATION events from the Workflow Engine.
     * Expected payload type: MitigationCommandData (from the SDK).
     */
    private void onMitigationCommand(Event<?> event) {
        if (!running) {
            return;
        }

        Object data = event.getData();
        if (!(data instanceof MitigationCommandData)) {
            helper.log(getName(), "WARN",
                    "Received INITIATE_MITIGATION with unexpected payload type: " +
                            (data == null ? "null" : data.getClass().getName()));
            return;
        }

        MitigationCommandData command = (MitigationCommandData) data;
        String targetHost = command.getTargetHost();
        MitigationAction action = command.getAction();
        String justification = command.getJustification();

        helper.log(getName(), "INFO",
                String.format("Handling mitigation command %s for target %s (workflow=%s, reason=%s)",
                        action,
                        targetHost,
                        command.getWorkflowInstanceId(),
                        justification));

        try {
            if (action == MitigationAction.BLOCK_IP ||
                action == MitigationAction.QUARANTINE ||
                action == MitigationAction.ISOLATE_VLAN) {
                simulateBlockIp(targetHost, action);
            } else {
                helper.log(getName(), "WARN",
                        "MitigationAction " + action + " is not yet implemented in OpenDaylightModule stub");
            }
        } catch (Exception e) {
            helper.log(getName(), "ERROR",
                    "Error while simulating mitigation for target " + targetHost + ": " + e.getMessage());
        }
    }

    /**
     * Handle SDN_INSTALL_FLOW events.
     * In this stub version we simply log the received payload.
     */
    private void onSdnInstallFlow(Event<?> event) {
        if (!running) {
            return;
        }

        Object data = event.getData();
        helper.log(getName(), "INFO",
                "Received SDN_INSTALL_FLOW event with payload type=" +
                        (data == null ? "null" : data.getClass().getName()));
        helper.log(getName(), "DEBUG",
                "SDN_INSTALL_FLOW payload (to be mapped to RESTCONF in future): " + String.valueOf(data));
    }

    /**
     * Stub: simulate an IP-based block/containment in OpenDaylight.
     */
    private void simulateBlockIp(String targetHost, MitigationAction action) {
        if (targetHost == null || targetHost.isBlank()) {
            helper.log(getName(), "WARN", "No targetHost provided for mitigation; skipping");
            return;
        }

        String message = String.format(
                "[STUB] Would call OpenDaylight RESTCONF at %s to apply %s for host %s (user=%s)",
                odlBaseUrl,
                action.getAction(),
                targetHost,
                odlUsername
        );
        helper.log(getName(), "INFO", message);
    }

    /**
     * Load module and OpenDaylight configuration from properties file.
     */
    private void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_PATH)) {
            props.load(in);

            moduleId = props.getProperty("module.id", moduleId);
            moduleName = props.getProperty("module.name", moduleName);
            moduleType = props.getProperty("module.type", moduleType);

            odlBaseUrl = props.getProperty("odl.base_url", odlBaseUrl);
            odlUsername = props.getProperty("odl.username", odlUsername);
            odlPassword = props.getProperty("odl.password", odlPassword);

            System.out.println("[OpenDaylightModule] Loaded config from " + CONFIG_PATH);
        } catch (IOException e) {
            System.out.println("[OpenDaylightModule][WARN] Could not load config (using defaults): " + e.getMessage());
        }
    }
}
