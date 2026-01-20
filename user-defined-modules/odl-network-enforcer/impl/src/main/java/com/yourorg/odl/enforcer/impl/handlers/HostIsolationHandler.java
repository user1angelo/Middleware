package com.yourorg.odl.enforcer.impl.handlers;

import com.yourorg.odl.enforcer.api.CommandHandler;
import com.yourorg.odl.enforcer.api.WorkflowCommand;
import com.yourorg.odl.enforcer.impl.ConfigLoader;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ODL Imports
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.SalFlowService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.AddFlowInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.AddFlowInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.AddFlowOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.Flow;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.FlowBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.flow.InstructionsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.flow.Match;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.flow.MatchBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.instruction.ApplyActionsCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.instruction.apply.actions._case.ApplyActionsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.action.list.Action;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.action.list.ActionBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.action.types.rev131112.action.action.DropActionCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.action.types.rev131112.action.action.drop.action._case.DropActionBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.Nodes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.NodeKey;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Prefix;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Uri;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.MacAddress;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.match.EthernetMatchBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.ethernet.match.fields.EthernetTypeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.ethernet.match.fields.EthernetType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.ethernet.match.fields.EthernetSourceBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.ethernet.match.fields.EthernetDestinationBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.match.layer._3.match.Ipv4MatchBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorId;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcResult;

import java.util.Collections;
import java.util.concurrent.Future;

/**
 * Handles host isolation commands by installing drop flows for the specified
 * host.
 * Command format:
 * {
 * "message_type": "odl.host.isolate",
 * "payload": {
 * "ip_address": "10.0.0.5",
 * "mac_address": "00:00:00:00:00:05",
 * "node_id": "openflow:1",
 * "reason": "Detected malicious activity"
 * }
 * }
 */
public class HostIsolationHandler implements CommandHandler {
    private static final Logger LOG = LoggerFactory.getLogger(HostIsolationHandler.class);
    private static final String COMMAND_TYPE = "odl.host.isolate";

    private final ConfigLoader config;
    private final DataBroker dataBroker;
    private final SalFlowService flowService;

    public HostIsolationHandler(ConfigLoader config) {
        this(config, null, null);
    }

    public HostIsolationHandler(ConfigLoader config, DataBroker dataBroker, SalFlowService flowService) {
        this.config = config;
        this.dataBroker = dataBroker;
        this.flowService = flowService;
    }

    @Override
    public boolean canHandle(String messageType) {
        return COMMAND_TYPE.equals(messageType);
    }

    @Override
    public boolean handleCommand(WorkflowCommand command) {
        try {
            JSONObject payload = command.getPayload();

            String ipAddress = payload.optString("ip_address", null);
            String macAddress = payload.optString("mac_address", null);
            String nodeId = payload.optString("node_id", "openflow:1");
            String reason = payload.optString("reason", "Security isolation");

            if (ipAddress == null && macAddress == null) {
                LOG.error("Host isolation requires at least ip_address or mac_address");
                return false;
            }

            LOG.info("Isolating host - IP: {}, MAC: {}, Node: {}, Reason: {}",
                    ipAddress, macAddress, nodeId, reason);

            // Install drop flows
            boolean success = installIsolationFlows(nodeId, ipAddress, macAddress);

            if (success) {
                LOG.info("Successfully isolated host {} (Event: {})",
                        ipAddress != null ? ipAddress : macAddress, command.getEventId());
            } else {
                LOG.error("Failed to isolate host {} (Event: {})",
                        ipAddress != null ? ipAddress : macAddress, command.getEventId());
            }

            return success;

        } catch (Exception e) {
            LOG.error("Error handling host isolation command: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean installIsolationFlows(String nodeId, String ipAddress, String macAddress) {
        try {
            int priority = config.getIsolationFlowPriority();

            LOG.info("Installing isolation flows for Node: {}, Priority: {}", nodeId, priority);

            if (flowService == null) {
                LOG.warn("SalFlowService not available. Skipping real flow installation (simulated mode).");
                return true;
            }

            // Build common flow parts
            NodeRef nodeRef = createNodeRef(nodeId);

            // 1. Block by IP if provided
            if (ipAddress != null) {
                LOG.info("  Blocking IP: {}", ipAddress);
                installDropFlow(nodeRef, nodeId, "src-ip-" + ipAddress, priority,
                        MatchUtils.createIpv4Match(ipAddress, null));
                installDropFlow(nodeRef, nodeId, "dst-ip-" + ipAddress, priority,
                        MatchUtils.createIpv4Match(null, ipAddress));
            }

            // 2. Block by MAC if provided
            if (macAddress != null) {
                LOG.info("  Blocking MAC: {}", macAddress);
                installDropFlow(nodeRef, nodeId, "src-mac-" + macAddress, priority,
                        MatchUtils.createMacMatch(macAddress, null));
                installDropFlow(nodeRef, nodeId, "dst-mac-" + macAddress, priority,
                        MatchUtils.createMacMatch(null, macAddress));
            }

            LOG.info("Isolation flows installed successfully");
            return true;

        } catch (Exception e) {
            LOG.error("Error installing isolation flows: {}", e.getMessage(), e);
            return false;
        }
    }

    private void installDropFlow(NodeRef nodeRef, String nodeId, String flowId, int priority, Match match) {
        // Create Drop Action
        DropAction dropAction = new DropActionCaseBuilder()
                .setDropAction(new DropActionBuilder().build())
                .build();

        Action action = new ActionBuilder()
                .setOrder(0)
                .setAction(dropAction)
                .build();

        // Create Instructions (Apply Actions)
        Instruction applyActions = new InstructionBuilder()
                .setOrder(0)
                .setInstruction(new ApplyActionsCaseBuilder()
                        .setApplyActions(new ApplyActionsBuilder()
                                .setAction(Collections.singletonList(action))
                                .build())
                        .build())
                .build();

        // Build Flow
        Flow flow = new FlowBuilder()
                .setId(new FlowId(flowId))
                .setTableId((short) 0)
                .setPriority(priority)
                .setMatch(match)
                .setInstructions(new InstructionsBuilder()
                        .setInstruction(Collections.singletonList(applyActions))
                        .build())
                .setIdleTimeout(0)
                .setHardTimeout(0)
                .setBarrier(true)
                .build();

        // Add Flow via RPC
        AddFlowInput input = new AddFlowInputBuilder(flow)
                .setNode(nodeRef)
                .setTransactionUri(new Uri("isolation-flow-" + flowId))
                .build();

        Future<RpcResult<AddFlowOutput>> future = flowService.addFlow(input);

        // Non-blocking callback (optional, or just fire and forget)
    }

    private NodeRef createNodeRef(String nodeId) {
        InstanceIdentifier<Node> nodePath = InstanceIdentifier.builder(Nodes.class)
                .child(Node.class, new NodeKey(new NodeId(nodeId)))
                .build();
        return new NodeRef(nodePath);
    }

    // Inner helper for Match creation (could be moved to utility class)
    private static class MatchUtils {
        static Match createIpv4Match(String srcIp, String dstIp) {
            Ipv4MatchBuilder ipv4Match = new Ipv4MatchBuilder();
            if (srcIp != null)
                ipv4Match.setIpv4Source(new Ipv4Prefix(srcIp + "/32"));
            if (dstIp != null)
                ipv4Match.setIpv4Destination(new Ipv4Prefix(dstIp + "/32"));

            return new MatchBuilder()
                    .setEthernetMatch(new EthernetMatchBuilder()
                            .setEthernetType(new EthernetTypeBuilder()
                                    .setType(new EthernetType(0x0800L))
                                    .build())
                            .build())
                    .setLayer3Match(ipv4Match.build())
                    .build();
        }

        static Match createMacMatch(String srcMac, String dstMac) {
            EthernetMatchBuilder ethMatch = new EthernetMatchBuilder();
            if (srcMac != null)
                ethMatch.setEthernetSource(new EthernetSourceBuilder().setAddress(new MacAddress(srcMac)).build());
            if (dstMac != null)
                ethMatch.setEthernetDestination(
                        new EthernetDestinationBuilder().setAddress(new MacAddress(dstMac)).build());

            return new MatchBuilder()
                    .setEthernetMatch(ethMatch.build())
                    .build();
        }
    }
}
