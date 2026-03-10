package com.yourorg.odl.enforcer.impl.handlers;

import com.yourorg.odl.enforcer.api.WorkflowCommand;
import com.yourorg.odl.enforcer.impl.ConfigLoader;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.SalFlowService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.service.rev130819.AddFlowInput;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class HostIsolationHandlerTest {

    @Mock
    private ConfigLoader config;

    @Mock
    private DataBroker dataBroker;

    @Mock
    private SalFlowService flowService;

    private HostIsolationHandler handler;

    @Before
    public void setUp() {
        when(config.getIsolationFlowPriority()).thenReturn(100);
        handler = new HostIsolationHandler(config, dataBroker, flowService);
    }

    @Test
    public void testCanHandle() {
        assertTrue(handler.canHandle("odl.host.isolate"));
        assertFalse(handler.canHandle("odl.flow.add"));
    }

    @Test
    public void testHandleCommand_MissingPayload() {
        WorkflowCommand command = mock(WorkflowCommand.class);
        when(command.getPayload()).thenReturn(new JSONObject());

        assertFalse(handler.handleCommand(command));
        verifyZeroInteractions(flowService);
    }

    @Test
    public void testHandleCommand_IsolateIp() {
        JSONObject payload = new JSONObject();
        payload.put("ip_address", "10.0.0.1");
        payload.put("node_id", "openflow:1");

        WorkflowCommand command = mock(WorkflowCommand.class);
        when(command.getPayload()).thenReturn(payload);

        assertTrue(handler.handleCommand(command));

        // Should initiate flow addition (2 calls: src-ip and dst-ip)
        verify(flowService, times(2)).addFlow(any(AddFlowInput.class));
    }

    @Test
    public void testHandleCommand_IsolateMac() {
        JSONObject payload = new JSONObject();
        payload.put("mac_address", "00:00:00:00:00:01");
        payload.put("node_id", "openflow:1");

        WorkflowCommand command = mock(WorkflowCommand.class);
        when(command.getPayload()).thenReturn(payload);

        assertTrue(handler.handleCommand(command));

        // Should initiate flow addition (2 calls: src-mac and dst-mac)
        verify(flowService, times(2)).addFlow(any(AddFlowInput.class));
    }

    @Test
    public void testHandleCommand_SimulationMode() {
        // Test with null services
        handler = new HostIsolationHandler(config);

        JSONObject payload = new JSONObject();
        payload.put("ip_address", "10.0.0.1");

        WorkflowCommand command = mock(WorkflowCommand.class);
        when(command.getPayload()).thenReturn(payload);

        // Should still return true (simulated success)
        assertTrue(handler.handleCommand(command));
    }
}
