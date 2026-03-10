package com.yourorg.odl.enforcer.impl;

import com.yourorg.odl.enforcer.api.CommandHandler;
import com.yourorg.odl.enforcer.impl.handlers.FlowAddHandler;
import com.yourorg.odl.enforcer.impl.handlers.HostIsolationHandler;
import com.yourorg.odl.enforcer.impl.handlers.TopologyDiscoveryHandler;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * OSGi Bundle Activator for ODL Network Enforcer.
 * Initializes the RabbitMQ listener and command handlers.
 */
public class Activator implements BundleActivator {
    private static final Logger LOG = LoggerFactory.getLogger(Activator.class);

    private WorkflowCommandListener listener;
    private Thread listenerThread;

    @Override
    public void start(BundleContext context) throws Exception {
        LOG.info("Starting ODL Network Enforcer bundle...");

        try {
            // Load configuration
            ConfigLoader config = new ConfigLoader();

            // Get ODL services
            org.opendaylight.controller.md.sal.binding.api.DataBroker dataBroker = getService(context,
                    org.opendaylight.controller.md.sal.binding.api.DataBroker.class);
            org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.SalFlowService flowService = getService(
                    context, org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.SalFlowService.class);

            if (dataBroker == null || flowService == null) {
                LOG.warn("ODL services not found. Enforcer will run in simulation mode.");
            }

            // Initialize command handlers
            List<CommandHandler> handlers = new ArrayList<>();
            handlers.add(new FlowAddHandler(config));
            handlers.add(new HostIsolationHandler(config, dataBroker, flowService));
            handlers.add(new TopologyDiscoveryHandler(config));

            LOG.info("Registered {} command handlers", handlers.size());

            // Start RabbitMQ listener in separate thread
            listener = new WorkflowCommandListener(config, handlers);
            listenerThread = new Thread(() -> {
                try {
                    listener.start();
                } catch (Exception e) {
                    LOG.error("Error in WorkflowCommandListener: {}", e.getMessage(), e);
                }
            }, "WorkflowCommandListener-Thread");

            listenerThread.start();

            LOG.info("ODL Network Enforcer bundle started successfully");

        } catch (Exception e) {
            LOG.error("Failed to start ODL Network Enforcer: {}", e.getMessage(), e);
            throw e;
        }
    }

    // Helper to get OSGi service
    private <T> T getService(BundleContext context, Class<T> serviceClass) {
        org.osgi.framework.ServiceReference<T> ref = context.getServiceReference(serviceClass);
        return ref != null ? context.getService(ref) : null;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        LOG.info("Stopping ODL Network Enforcer bundle...");

        try {
            if (listener != null) {
                listener.stop();
            }

            if (listenerThread != null && listenerThread.isAlive()) {
                listenerThread.interrupt();
                listenerThread.join(5000); // Wait up to 5 seconds
            }

            LOG.info("ODL Network Enforcer bundle stopped successfully");

        } catch (Exception e) {
            LOG.error("Error stopping ODL Network Enforcer: {}", e.getMessage(), e);
            throw e;
        }
    }
}
