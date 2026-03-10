import React, { useState, useEffect } from 'react';
import '../App.css';
import { odlAPI } from '../services/api';

const ALLOWLIST_PROFILES = {
    none: {
        label: 'None',
        managementHost: '',
        managementPorts: ''
    },
    win11_jump_host: {
        label: 'Win11 SOC Jump Host',
        managementHost: '192.168.56.20/32',
        managementPorts: '22,3389,443'
    },
    custom: {
        label: 'Custom',
        managementHost: '',
        managementPorts: ''
    }
};

function NetworkControl() {
    const [topology, setTopology] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [selectedHost, setSelectedHost] = useState({ ip: '', mac: '' });
    const [selectedLocalization, setSelectedLocalization] = useState({
        status: 'fallback',
        node: 'openflow:1',
        port: null,
        source_of_truth: 'default-node'
    });
    const [isolateStatus, setIsolateStatus] = useState('');
    const [removeIsolationStatus, setRemoveIsolationStatus] = useState('');
    const [scanStatus, setScanStatus] = useState('');
    const [startIp, setStartIp] = useState('');
    const [isAutoScan, setIsAutoScan] = useState(false);
    const [durationMinutes, setDurationMinutes] = useState(30);
    const [autoExpire, setAutoExpire] = useState(true);
    const [rollbackNote, setRollbackNote] = useState('Manual rollback from dashboard');
    const [activeMitigations, setActiveMitigations] = useState([]);
    const [mitigationError, setMitigationError] = useState('');
    const [policyMode, setPolicyMode] = useState('strict');
    const [allowlistProfile, setAllowlistProfile] = useState('win11_jump_host');
    const [managementHost, setManagementHost] = useState(ALLOWLIST_PROFILES.win11_jump_host.managementHost);
    const [managementPorts, setManagementPorts] = useState(ALLOWLIST_PROFILES.win11_jump_host.managementPorts);
    const [containArp, setContainArp] = useState(false);
    const [containDhcp, setContainDhcp] = useState(false);

    const fetchTopology = async () => {
        // Silent loading for polling if we already have data
        if (!topology) setLoading(true);
        setError(null);
        try {
            const response = await odlAPI.getTopology();
            setTopology(response.data);
        } catch (err) {
            if (!topology) setError(err.message);
            console.error(err);
        } finally {
            if (!topology) setLoading(false);
        }
    };

    const triggerScan = async () => {
        try {
            setScanStatus(isAutoScan ? 'Auto-Scanning...' : 'Scanning...');

            const body = {};
            if (startIp) body.start_ip = startIp;

            await odlAPI.triggerScan(body);

            // Refresh topology shortly after triggering scan
            setTimeout(fetchTopology, 1000);
            setTimeout(() => setScanStatus(''), 3000);
        } catch (err) {
            console.error("Scan trigger failed:", err);
            // Don't show error in UI for background access to avoid annoying flickering
        }
    };

    const handleIsolate = async () => {
        if (!selectedHost.ip && !selectedHost.mac) {
            alert('Please enter an IP or MAC address');
            return;
        }

        try {
            const parsedPorts = managementPorts
                .split(',')
                .map((port) => Number(port.trim()))
                .filter((port) => Number.isInteger(port) && port > 0 && port <= 65535);

            setIsolateStatus('Sending command...');
            const response = await odlAPI.isolateHost({
                ip: selectedHost.ip,
                mac: selectedHost.mac,
                auto_expire: autoExpire,
                duration_ms: autoExpire ? Number(durationMinutes) * 60 * 1000 : 0,
                rollback_note: rollbackNote,
                policy_mode: policyMode,
                allowlist_profile: allowlistProfile,
                policy: {
                    policy_mode: policyMode,
                    allowlist_profile: allowlistProfile,
                    management_host: managementHost,
                    management_ports: parsedPorts,
                    contain_arp: containArp,
                    contain_dhcp: containDhcp
                }
            });

            const policySummary = response.data?.effective_policy
                ? `mode=${response.data.effective_policy.policy_mode}, allowlist=${response.data.effective_policy.allowlist_profile}, mgmt=${response.data.effective_policy.management_host || 'none'}, ports=${(response.data.effective_policy.management_ports || []).join(',') || 'none'}, arp=${response.data.effective_policy.contain_arp ? 'on' : 'off'}, dhcp=${response.data.effective_policy.contain_dhcp ? 'on' : 'off'}`
                : 'policy unavailable';

            const loc = response.data?.localization;
            const locationSummary = loc
                ? `target=${loc.node}${loc.port ? `/${loc.port}` : ''} (${loc.status})`
                : 'target location unavailable';

            setIsolateStatus(
                `Isolation command sent. mitigation_id=${response.data.mitigation_id || 'n/a'} | ${locationSummary} | ${policySummary}`
            );
            await fetchMitigations();
        } catch (err) {
            setIsolateStatus(`❌ Error: ${err.message}`);
        }
    };

    const handleRemoveIsolation = async () => {
        if (!selectedHost.ip && !selectedHost.mac) {
            alert('Please enter an IP or MAC address');
            return;
        }

        try {
            setRemoveIsolationStatus('Sending command...');
            await odlAPI.removeIsolation({
                ip: selectedHost.ip,
                mac: selectedHost.mac,
                rollback_note: rollbackNote
            });
            setRemoveIsolationStatus('✅ Remove isolation command sent successfully!');
            await fetchMitigations();
        } catch (err) {
            setRemoveIsolationStatus(`❌ Error: ${err.message}`);
        }
    };

    const fetchMitigations = async () => {
        try {
            const response = await odlAPI.listMitigations();
            setActiveMitigations(response.data.mitigations || []);
            setMitigationError('');
        } catch (err) {
            setMitigationError(err.message);
        }
    };

    const handleClearMitigation = async (mitigationId) => {
        try {
            await odlAPI.clearMitigation(mitigationId, { rollback_note: rollbackNote });
            await fetchMitigations();
        } catch (err) {
            setMitigationError(err.message);
        }
    };

    const handleExtendMitigation = async (mitigationId, extraMinutes = 10) => {
        try {
            await odlAPI.extendMitigation(mitigationId, { duration_ms: extraMinutes * 60 * 1000 });
            await fetchMitigations();
        } catch (err) {
            setMitigationError(err.message);
        }
    };

    const formatCountdown = (expiresAt) => {
        if (!expiresAt) {
            return 'No expiry';
        }
        const remainingMs = new Date(expiresAt).getTime() - Date.now();
        if (remainingMs <= 0) {
            return 'Expired';
        }
        const totalSeconds = Math.floor(remainingMs / 1000);
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;
        return `${minutes}m ${seconds}s`;
    };

    useEffect(() => {
        // 1. Initial fetch
        fetchTopology();
        fetchMitigations();

        // 2. Poll Topology every 5 seconds (Reads ODL state)
        const topologyInterval = setInterval(fetchTopology, 5000);
        const mitigationInterval = setInterval(fetchMitigations, 1000);

        // 3. Auto-Scan logic
        let scanInterval = null;
        if (isAutoScan) {
            // Trigger immediately when toggled on
            triggerScan();
            scanInterval = setInterval(triggerScan, 15000);
        }

        return () => {
            clearInterval(topologyInterval);
            clearInterval(mitigationInterval);
            if (scanInterval) clearInterval(scanInterval);
        };
    }, [isAutoScan]); // Re-run effect when isAutoScan changes

    useEffect(() => {
        const profile = ALLOWLIST_PROFILES[allowlistProfile];
        if (!profile) {
            return;
        }

        if (allowlistProfile !== 'custom') {
            setManagementHost(profile.managementHost);
            setManagementPorts(profile.managementPorts);
        }
    }, [allowlistProfile]);

    const renderStatusChip = (status) => {
        const normalized = status || 'fallback';
        let color = '#6c757d';
        if (normalized === 'resolved') color = '#198754';
        if (normalized === 'partial') color = '#fd7e14';
        if (normalized === 'fallback') color = '#6f42c1';
        return (
            <span style={{ background: color, color: '#fff', borderRadius: '12px', padding: '3px 8px', fontSize: '0.75em' }}>
                {normalized}
            </span>
        );
    };

    // Supports both enriched backend response and legacy raw topology.
    const getHosts = () => {
        if (!topology) return [];

        if (Array.isArray(topology.hosts)) {
            return topology.hosts.map((host) => ({
                id: host.id,
                mac: host.mac || 'Unknown',
                ip: host.ip || 'Unknown',
                attachment: host.port || 'Unknown',
                node: host.node || 'openflow:1',
                port: host.port || 'Unknown',
                status: host.status || 'fallback',
                source_of_truth: host.source_of_truth || 'unknown'
            }));
        }

        const rawTopology = topology.raw_topology || topology;
        if (!rawTopology['network-topology'] || !rawTopology['network-topology'].topology) return [];

        const hosts = [];
        const topo = rawTopology['network-topology'].topology[0];
        if (!topo.node) return [];

        topo.node.forEach(node => {
            if (node['node-id'].startsWith('host:')) {
                const mac = node['node-id'].replace('host:', '');
                const ip = node['host-tracker-service:addresses']?.[0]?.ip || 'Unknown';

                hosts.push({
                    id: node['node-id'],
                    mac: mac,
                    ip: ip,
                    attachment: node['host-tracker-service:attachment-points']?.[0]?.['tp-id'] || 'Unknown',
                    node: 'openflow:1',
                    port: node['host-tracker-service:attachment-points']?.[0]?.['tp-id'] || 'Unknown',
                    status: 'partial',
                    source_of_truth: 'legacy-parser'
                });
            }
        });
        return hosts;
    };

    const hosts = getHosts();

    const effectivePolicyPreview = {
        policy_mode: policyMode,
        allowlist_profile: allowlistProfile,
        management_host: managementHost || '(none)',
        management_ports: managementPorts || '(none)',
        contain_arp: containArp,
        contain_dhcp: containDhcp,
        target_node: selectedLocalization.node,
        target_port: selectedLocalization.port || '(unknown)',
        localization_status: selectedLocalization.status
    };

    return (
        <div className="App-page">
            <header className="App-header">
                <h1>Network Control Center</h1>
            </header>

            <div className="container" style={{ padding: '20px' }}>

                {/* Topology Section */}
                <section className="card">
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '15px' }}>
                        <h2>
                            🌐 Network Topology
                            {isAutoScan && (
                                <span className="status-badge status-running" style={{ fontSize: '0.6em', verticalAlign: 'middle', marginLeft: '10px' }}>
                                    Auto-Scanning (15s)
                                </span>
                            )}
                        </h2>

                        <div style={{ display: 'flex', gap: '10px', alignItems: 'center', background: '#333', padding: '10px', borderRadius: '8px' }}>
                            <div style={{ display: 'flex', flexDirection: 'column' }}>
                                <label style={{ fontSize: '0.8em', color: '#aaa', marginBottom: '2px' }}>Start IP (Optional):</label>
                                <input
                                    type="text"
                                    value={startIp}
                                    onChange={(e) => setStartIp(e.target.value)}
                                    placeholder="e.g. 192.168.1.1"
                                    style={{ padding: '5px', borderRadius: '4px', border: '1px solid #555', background: '#222', color: 'white', width: '120px' }}
                                />
                            </div>

                            <div style={{ borderLeft: '1px solid #555', height: '30px', margin: '0 5px' }}></div>

                            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', gap: '5px', fontSize: '0.9em' }}>
                                <input
                                    type="checkbox"
                                    checked={isAutoScan}
                                    onChange={(e) => setIsAutoScan(e.target.checked)}
                                />
                                Auto Scan
                            </label>

                            <button onClick={() => { triggerScan(); }} disabled={loading} className="btn-primary">
                                Scan Once
                            </button>
                        </div>
                    </div>

                    {error && <div className="alert alert-error">{error}</div>}

                    {topology ? (
                        <div className="topology-view">
                            <h3>Discovered Hosts ({hosts.length})</h3>
                            {hosts.length === 0 ? (
                                <p>No hosts discovered. (Topology might be empty or restricted)</p>
                            ) : (
                                <table className="data-table">
                                    <thead>
                                        <tr>
                                            <th>MAC Address</th>
                                            <th>IP Address</th>
                                            <th>Node</th>
                                            <th>Switch Port</th>
                                            <th>Localization</th>
                                            <th>Action</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {hosts.map(host => (
                                            <tr key={host.id}>
                                                <td>{host.mac}</td>
                                                <td>{host.ip}</td>
                                                <td>{host.node || 'openflow:1'}</td>
                                                <td>{host.attachment}</td>
                                                <td>{renderStatusChip(host.status)}</td>
                                                <td>
                                                    <div style={{ display: 'flex', gap: '5px' }}>
                                                        <button
                                                            className="btn-danger-outline"
                                                            onClick={() => {
                                                                setSelectedHost({ ip: host.ip !== 'Unknown' ? host.ip : '', mac: host.mac });
                                                                setSelectedLocalization({
                                                                    status: host.status || 'fallback',
                                                                    node: host.node || 'openflow:1',
                                                                    port: host.port !== 'Unknown' ? host.port : null,
                                                                    source_of_truth: host.source_of_truth || 'unknown'
                                                                });
                                                            }}
                                                        >
                                                            Isolate
                                                        </button>
                                                        <button
                                                            className="btn-success-outline"
                                                            onClick={() => {
                                                                setSelectedHost({ ip: host.ip !== 'Unknown' ? host.ip : '', mac: host.mac });
                                                                setSelectedLocalization({
                                                                    status: host.status || 'fallback',
                                                                    node: host.node || 'openflow:1',
                                                                    port: host.port !== 'Unknown' ? host.port : null,
                                                                    source_of_truth: host.source_of_truth || 'unknown'
                                                                });
                                                            }}
                                                        >
                                                            Unblock
                                                        </button>
                                                    </div>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            )}

                            <details style={{ marginTop: '20px' }}>
                                <summary>Raw Topology JSON</summary>
                                <pre style={{ textAlign: 'left', background: '#333', padding: '10px' }}>
                                    {JSON.stringify(topology.raw_topology || topology, null, 2)}
                                </pre>
                            </details>
                        </div>
                    ) : (
                        <p>Loading topology...</p>
                    )}
                </section>

                {/* Manual Isolation Section */}
                <section className="card" style={{ marginTop: '20px', borderLeft: '5px solid #ff4444' }}>
                    <h2>🛡️ Manual Host Isolation</h2>
                    <p>Select a host from above or enter details manually.</p>

                    <div className="form-group">
                        <label>Policy mode:</label>
                        <select value={policyMode} onChange={(e) => setPolicyMode(e.target.value)}>
                            <option value="strict">strict (full host containment)</option>
                            <option value="allowlist-managed">allowlist-managed (SOC access allowed)</option>
                        </select>
                    </div>

                    <div className="form-group">
                        <label>Allowlist profile:</label>
                        <select value={allowlistProfile} onChange={(e) => setAllowlistProfile(e.target.value)}>
                            <option value="none">{ALLOWLIST_PROFILES.none.label}</option>
                            <option value="win11_jump_host">{ALLOWLIST_PROFILES.win11_jump_host.label}</option>
                            <option value="custom">{ALLOWLIST_PROFILES.custom.label}</option>
                        </select>
                    </div>

                    <div className="form-group">
                        <label>Management host IP/CIDR:</label>
                        <input
                            type="text"
                            value={managementHost}
                            onChange={(e) => setManagementHost(e.target.value)}
                            placeholder="e.g. 192.168.56.20/32"
                        />
                    </div>

                    <div className="form-group">
                        <label>Allowed management ports (comma separated):</label>
                        <input
                            type="text"
                            value={managementPorts}
                            onChange={(e) => setManagementPorts(e.target.value)}
                            placeholder="e.g. 22,3389,443"
                        />
                    </div>

                    <div className="form-group">
                        <label>Contain ARP traffic:</label>
                        <input
                            type="checkbox"
                            checked={containArp}
                            onChange={(e) => setContainArp(e.target.checked)}
                        />
                    </div>

                    <div className="form-group">
                        <label>Contain DHCP traffic:</label>
                        <input
                            type="checkbox"
                            checked={containDhcp}
                            onChange={(e) => setContainDhcp(e.target.checked)}
                        />
                    </div>

                    <div className="form-group">
                        <label>Target IP Address:</label>
                        <input
                            type="text"
                            value={selectedHost.ip}
                            onChange={(e) => setSelectedHost({ ...selectedHost, ip: e.target.value })}
                            placeholder="10.0.0.x"
                        />
                    </div>

                    <div className="form-group">
                        <label>Target MAC Address:</label>
                        <input
                            type="text"
                            value={selectedHost.mac}
                            onChange={(e) => setSelectedHost({ ...selectedHost, mac: e.target.value })}
                            placeholder="00:00:00:00:00:00"
                        />
                    </div>

                    <div className="form-group">
                        <label>Auto-expire quarantine:</label>
                        <input
                            type="checkbox"
                            checked={autoExpire}
                            onChange={(e) => setAutoExpire(e.target.checked)}
                        />
                    </div>

                    <div className="form-group">
                        <label>Duration (minutes):</label>
                        <input
                            type="number"
                            min="1"
                            value={durationMinutes}
                            onChange={(e) => setDurationMinutes(e.target.value)}
                            disabled={!autoExpire}
                        />
                    </div>

                    <div className="form-group">
                        <label>Rollback note:</label>
                        <input
                            type="text"
                            value={rollbackNote}
                            onChange={(e) => setRollbackNote(e.target.value)}
                            placeholder="Reason for rollback/clear"
                        />
                    </div>

                    <div style={{ background: '#2b2f36', padding: '12px', borderRadius: '8px', marginTop: '10px' }}>
                        <strong>Effective policy summary</strong>
                        <pre style={{ margin: '8px 0 0', textAlign: 'left', whiteSpace: 'pre-wrap' }}>
                            {JSON.stringify(effectivePolicyPreview, null, 2)}
                        </pre>
                    </div>

                    <div style={{ display: 'flex', gap: '10px', marginTop: '10px' }}>
                        <button onClick={handleIsolate} className="btn-danger">
                            🚨 ISOLATE HOST
                        </button>

                        <button onClick={handleRemoveIsolation} className="btn-success">
                            ✅ REMOVE ISOLATION
                        </button>
                    </div>

                    {isolateStatus && <p style={{ marginTop: '10px', fontWeight: 'bold' }}>{isolateStatus}</p>}
                    {removeIsolationStatus && <p style={{ marginTop: '10px', fontWeight: 'bold' }}>{removeIsolationStatus}</p>}
                </section>

                <section className="card" style={{ marginTop: '20px' }}>
                    <h2>⏱️ Active Mitigations</h2>
                    {mitigationError && <p style={{ color: '#ff6b6b' }}>{mitigationError}</p>}

                    {activeMitigations.length === 0 ? (
                        <p>No active mitigations.</p>
                    ) : (
                        <table className="data-table">
                            <thead>
                                <tr>
                                    <th>Mitigation ID</th>
                                    <th>Target</th>
                                    <th>Status</th>
                                    <th>Expiry</th>
                                    <th>Countdown</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {activeMitigations.map((mitigation) => (
                                    <tr key={mitigation.mitigation_id}>
                                        <td>{mitigation.mitigation_id}</td>
                                        <td>{mitigation.ip || mitigation.mac || 'Unknown'}</td>
                                        <td>{mitigation.status}</td>
                                        <td>{mitigation.expires_at || 'No expiry'}</td>
                                        <td>{formatCountdown(mitigation.expires_at)}</td>
                                        <td>
                                            <div style={{ display: 'flex', gap: '6px' }}>
                                                <button
                                                    className="btn-success-outline"
                                                    onClick={() => handleClearMitigation(mitigation.mitigation_id)}
                                                >
                                                    Clear
                                                </button>
                                                <button
                                                    className="btn-primary"
                                                    onClick={() => handleExtendMitigation(mitigation.mitigation_id, 10)}
                                                    disabled={!mitigation.auto_expire}
                                                >
                                                    +10m
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </section>
            </div>
        </div>
    );
}

export default NetworkControl;
