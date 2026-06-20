package com.nis1.thesis.udm.services;

import com.nis1.thesis.sdk.ModuleHelper;

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
import java.util.concurrent.TimeUnit;

/**
 * Service for active network scanning and topology discovery.
 * Ported from odl-network-enforcer's TopologyDiscoveryHandler.
 */
public class NetworkScannerService {

    private final ModuleHelper helper;
    private final String moduleName;

    public NetworkScannerService(ModuleHelper helper, String moduleName) {
        this.helper = helper;
        this.moduleName = moduleName;
    }

    /**
     * Perform an active network scan (Ping Sweep + ARP)
     *
     * @param startIp Optional start IP to define subnet. If null, active interface
     *                is used.
     * @return Map of IP -> MAC (or "Unknown")
     */
    public Map<String, String> scanNetwork(String startIp) {
        Map<String, String> results = new HashMap<>();

        try {
            // 1. Identify Subnet
            String baseIp = null;
            int subnetSize = 254;

            if (startIp != null && !startIp.isEmpty()) {
                helper.log(moduleName, "INFO", "Using provided start IP for scan: " + startIp);
                if (startIp.lastIndexOf('.') > 0) {
                    baseIp = startIp.substring(0, startIp.lastIndexOf('.') + 1);
                }
            } else {
                NetworkInterface networkInterface = getActiveNetworkInterface();
                if (networkInterface == null) {
                    helper.log(moduleName, "ERROR", "No active network interface found for scanning.");
                    return results;
                }

                InterfaceAddress subnet = getInterfaceAddress(networkInterface);
                if (subnet == null) {
                    helper.log(moduleName, "ERROR",
                            "No valid IPv4 address found on interface " + networkInterface.getDisplayName());
                    return results;
                }

                String localIp = subnet.getAddress().getHostAddress();
                short prefixLength = subnet.getNetworkPrefixLength();
                helper.log(moduleName, "INFO", "Scanning local network interface: " + networkInterface.getDisplayName()
                        + " (" + localIp + "/" + prefixLength + ")");
                baseIp = localIp.substring(0, localIp.lastIndexOf('.') + 1);

                if (prefixLength > 0 && prefixLength <= 32) {
                    int rawSize = (1 << (32 - prefixLength)) - 2;
                    subnetSize = Math.min(rawSize, 254);
                }
            }

            if (baseIp == null) {
                helper.log(moduleName, "ERROR", "Could not determine base IP for scan");
                return results;
            }

            // 2. Perform Ping Sweep
            helper.log(moduleName, "INFO", "Starting ping sweep on " + baseIp + "1 - " + baseIp + subnetSize);
            List<String> activeIps = performPingSweep(baseIp, subnetSize);
            helper.log(moduleName, "INFO", "Ping sweep complete. Found " + activeIps.size() + " active hosts.");

            // 3. Wait for kernel ARP cache to populate after pings
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 4. Resolve MAC Addresses (ARP)
            Map<String, String> arpTable = getArpTable();

            // 5. Correlate
            for (String ip : activeIps) {
                String mac = arpTable.getOrDefault(ip, "Unknown");
                results.put(ip, mac);
            }

        } catch (Exception e) {
            helper.log(moduleName, "ERROR", "Scan failed: " + e.getMessage());
            e.printStackTrace();
        }

        return results;
    }

    private List<String> performPingSweep(String baseIp, int limit) {
        List<String> activeIps = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(10);

        try {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 1; i <= limit; i++) {
                String targetIp = baseIp + i;
                futures.add(executor.submit(() -> {
                    try {
                        ProcessBuilder pb = new ProcessBuilder("ping", "-c", "1", "-W", "1", targetIp);
                        Process process = pb.start();
                        boolean completed = process.waitFor(2, TimeUnit.SECONDS);
                        if (!completed) {
                            process.destroyForcibly();
                        } else if (process.exitValue() == 0) {
                            activeIps.add(targetIp);
                        }
                    } catch (Exception ignored) {
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                }
            }

        } catch (Exception e) {
            helper.log(moduleName, "ERROR", "Ping sweep error: " + e.getMessage());
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        return activeIps;
    }

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
        return null;
    }

    private InterfaceAddress getInterfaceAddress(NetworkInterface iface) {
        for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
            if (addr.getAddress() instanceof java.net.Inet4Address) {
                return addr;
            }
        }
        return null;
    }

    private Map<String, String> getArpTable() {
        Map<String, String> arpTable = new HashMap<>();
        String os = System.getProperty("os.name").toLowerCase();

        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("arp", "-a");
            } else {
                // Linux/Unix: 'ip neighbor' preferred
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
            helper.log(moduleName, "WARN", "Failed to fetch ARP table: " + e.getMessage());
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
                if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && mac.contains("-")) {
                    table.put(ip, mac.replace('-', ':').toLowerCase());
                }
            }
        } else {
            // Linux 'ip neighbor' format: 192.168.1.1 dev eth0 lladdr 00:aa:bb:cc:dd:ee
            String[] parts = line.split("\\s+");
            if (parts.length >= 5) {
                String ip = parts[0];
                String mac = "";
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
}
