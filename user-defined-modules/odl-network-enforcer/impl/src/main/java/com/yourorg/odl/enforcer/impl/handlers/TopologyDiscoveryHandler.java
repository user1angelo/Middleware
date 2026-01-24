package com.yourorg.odl.enforcer.impl.handlers;

import com.yourorg.odl.enforcer.api.CommandHandler;
import com.yourorg.odl.enforcer.api.WorkflowCommand;
import com.yourorg.odl.enforcer.impl.ConfigLoader;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Handles topology discovery commands.
 * Command format:
 * {
 * "message_type": "odl.topology.discover",
 * "payload": {
 * "topology_id": "flow:1"
 * }
 * }
 */
public class TopologyDiscoveryHandler implements CommandHandler {
    private static final Logger LOG = LoggerFactory.getLogger(TopologyDiscoveryHandler.class);
    private static final String COMMAND_TYPE = "odl.topology.discover";

    private final ConfigLoader config;
    // TODO: Inject MD-SAL DataBroker when integrating with ODL

    public TopologyDiscoveryHandler(ConfigLoader config) {
        this.config = config;
    }

    @Override
    public boolean canHandle(String messageType) {
        return COMMAND_TYPE.equals(messageType);
    }

    @Override
    public boolean handleCommand(WorkflowCommand command) {
        try {
            JSONObject payload = command.getPayload();
            String topologyId = payload.optString("topology_id", "flow:1");

            LOG.info("Discovering topology {} (Event: {})", topologyId, command.getEventId());

            boolean success = discoverTopology(topologyId);

            if (success) {
                LOG.info("Successfully discovered topology (Event: {})", command.getEventId());
            } else {
                LOG.error("Failed to discover topology (Event: {})", command.getEventId());
            }

            return success;

        } catch (Exception e) {
            LOG.error("Error handling topology discovery command: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean discoverTopology(String topologyId) {
        LOG.info("Starting REAL active network scan for topology: {}", topologyId);

        try {
            // 1. Identify Local Subnet
            NetworkInterface networkInterface = getActiveNetworkInterface();
            if (networkInterface == null) {
                LOG.error("No active network interface found for scanning.");
                return false;
            }

            InterfaceAddress subnet = getInterfaceAddress(networkInterface);
            if (subnet == null) {
                LOG.error("No valid IPv4 address found on interface {}", networkInterface.getDisplayName());
                return false;
            }

            String localIp = subnet.getAddress().getHostAddress();
            short prefixLength = subnet.getNetworkPrefixLength();
            LOG.info("Scanning network: Interface={}, IP={}, Prefix=/{}",
                    networkInterface.getDisplayName(), localIp, prefixLength);

            // 2. Perform Ping Sweep
            List<String> activeIps = performPingSweep(subnet);
            LOG.info("Ping sweep complete. Found {} active hosts.", activeIps.size());

            // 3. Resolve MAC Addresses (ARP)
            Map<String, String> arpTable = getArpTable();

            // 4. Report Results
            LOG.info("=== Network Scan Results ===");
            for (String ip : activeIps) {
                String mac = arpTable.getOrDefault(ip, "Unknown (Local/Firewalled)");
                // If it's our own IP, we won't see it in ARP, but we know it exists
                if (ip.equals(localIp)) {
                    mac = getLocalMacAddress(networkInterface);
                }
                LOG.info("  Host: {} | MAC: {}", ip, mac);
            }
            LOG.info("============================");

            return true;

        } catch (Exception e) {
            LOG.error("Error performing network scan: {}", e.getMessage(), e);
            return false;
        }
    }

    // --- Helper Methods ---

    private NetworkInterface getActiveNetworkInterface() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            // Loopback and Down interfaces are not useful for scanning external network
            if (iface.isUp() && !iface.isLoopback() && !iface.isVirtual()) {
                // Check if it has an IPv4 address
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    if (addr.getAddress() instanceof java.net.Inet4Address) {
                        return iface;
                    }
                }
            }
        }
        return null; // Fallback or strict fail
    }

    private InterfaceAddress getInterfaceAddress(NetworkInterface iface) {
        for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
            if (addr.getAddress() instanceof java.net.Inet4Address) {
                return addr;
            }
        }
        return null;
    }

    private List<String> performPingSweep(InterfaceAddress subnet) {
        List<String> activeIps = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(50); // High concurrency for speed

        try {
            byte[] ip = subnet.getAddress().getAddress();
            int submask = 0xffffffff << (32 - subnet.getNetworkPrefixLength());
            int netAddress = ByteBuffer.wrap(ip).getInt() & submask;

            // Limit scan to Class C (/24) size for performance safety in this demo,
            // even if the subnet is larger. scanning /16 takes too long without specialized
            // tools.
            int limit = 254;

            List<Future<?>> futures = new ArrayList<>();

            // Iterate 1 to 254 (assuming /24 typical home/office network)
            // Ideally we'd calculate start/end IPs based on netmask
            String baseIp = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + ".";

            for (int i = 1; i <= limit; i++) {
                String targetIp = baseIp + i;
                futures.add(executor.submit(() -> {
                    try {
                        InetAddress address = InetAddress.getByName(targetIp);
                        // 500ms timeout per host is reasonable for LAN
                        if (address.isReachable(500)) {
                            activeIps.add(targetIp);
                        }
                    } catch (Exception ignored) {
                    }
                }));
            }

            // Wait for all pings to finish
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                }
            }

        } catch (Exception e) {
            LOG.error("Ping sweep failed: {}", e.getMessage());
        } finally {
            executor.shutdown();
        }

        return activeIps;
    }

    private Map<String, String> getArpTable() {
        Map<String, String> arpTable = new HashMap<>();
        String os = System.getProperty("os.name").toLowerCase();

        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("arp", "-a");
            } else {
                // Linux/Unix: 'arp -n' or 'ip neighbor'
                // Trying ip neighbor first as it's more modern
                pb = new ProcessBuilder("ip", "neighbor");
            }

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseArpLine(line, os, arpTable);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch ARP table: {}", e.getMessage());
        }
        return arpTable;
    }

    private void parseArpLine(String line, String os, Map<String, String> table) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("Interface"))
            return;

        if (os.contains("win")) {
            // Windows format: 192.168.1.1 00-aa-bb-cc-dd-ee dynamic
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                String ip = parts[0];
                String mac = parts[1];
                // basic validation
                if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && mac.contains("-")) {
                    table.put(ip, mac.replace('-', ':').toLowerCase());
                }
            }
        } else {
            // Linux 'ip neighbor' format: 192.168.1.1 dev eth0 lladdr 00:aa:bb:cc:dd:ee
            // STALE
            String[] parts = line.split("\\s+");
            if (parts.length >= 5) {
                String ip = parts[0];
                String mac = "";
                // find "lladdr" and take next
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].equals("lladdr") && i + 1 < parts.length) {
                        mac = parts[i + 1];
                        break;
                    }
                }
                if (!mac.isEmpty() && ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                    table.put(ip, mac.toLowerCase());
                }
            }
        }
    }

    private String getLocalMacAddress(NetworkInterface iface) {
        try {
            byte[] mac = iface.getHardwareAddress();
            if (mac == null)
                return "Unknown";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mac.length; i++) {
                sb.append(String.format("%02x%s", mac[i], (i < mac.length - 1) ? ":" : ""));
            }
            return sb.toString();
        } catch (SocketException e) {
            return "Unknown";
        }
    }
}
