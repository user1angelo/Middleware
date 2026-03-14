import React, { useState, useEffect } from 'react';
import '../App.css';

const LOCALIZATION_STYLE = {
    resolved: { background: 'rgba(72, 187, 120, 0.2)', color: '#48bb78', border: '1px solid rgba(72, 187, 120, 0.35)' },
    partial: { background: 'rgba(246, 173, 85, 0.2)', color: '#f6ad55', border: '1px solid rgba(246, 173, 85, 0.35)' },
    fallback: { background: 'rgba(252, 129, 129, 0.2)', color: '#fc8181', border: '1px solid rgba(252, 129, 129, 0.35)' }
};

const formatLocalizationStatus = (status) => {
    if (!status) return 'Unknown';
    return status.charAt(0).toUpperCase() + status.slice(1);
};

const POLICY_MODES = [
    { value: 'strict_bi_directional', label: 'Strict Bi-Directional (IP + MAC when available)' },
    { value: 'ip_only_bidirectional', label: 'IP-Only Bi-Directional' },
    { value: 'mac_only_bidirectional', label: 'MAC-Only Bi-Directional' }
];

const formatSeconds = (seconds) => {
    if (seconds === null || seconds === undefined) return 'No expiry';
    const safe = Math.max(Number(seconds) || 0, 0);
    const hours = Math.floor(safe / 3600);
    const minutes = Math.floor((safe % 3600) / 60);
    const secs = safe % 60;
    if (hours > 0) {
        return `${hours}h ${minutes}m ${secs}s`;
    }
    if (minutes > 0) {
        return `${minutes}m ${secs}s`;
    }
    return `${secs}s`;
};

const getRemainingSecondsFromExpiry = (expiresAt) => {
    if (!expiresAt) return null;
    const delta = Math.ceil((Date.parse(expiresAt) - Date.now()) / 1000);
    return Math.max(delta, 0);
};

function NetworkControl() {
    const [topology, setTopology] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [selectedHost, setSelectedHost] = useState({ ip: '', mac: '' });
    const [isolateStatus, setIsolateStatus] = useState('');
    const [removeIsolationStatus, setRemoveIsolationStatus] = useState('');
    const [scanStatus, setScanStatus] = useState('');
    const [startIp, setStartIp] = useState('');
    const [isAutoScan, setIsAutoScan] = useState(false);
    const [lastIsolationLocalization, setLastIsolationLocalization] = useState(null);
    const [lastRemoveLocalization, setLastRemoveLocalization] = useState(null);
    const [policyMode, setPolicyMode] = useState('strict_bi_directional');
    const [containArp, setContainArp] = useState(true);
    const [containDhcp, setContainDhcp] = useState(true);
    const [autoExpire, setAutoExpire] = useState(true);
    const [durationSeconds, setDurationSeconds] = useState(900);
    const [activeMitigations, setActiveMitigations] = useState([]);
    const [mitigationStatus, setMitigationStatus] = useState('');
    const [loadingMitigations, setLoadingMitigations] = useState(false);
    const [extendSecondsById, setExtendSecondsById] = useState({});

    const buildIsolationPayload = () => ({
        ip: selectedHost.ip || null,
        mac: selectedHost.mac || null,
        policy: {
            mode: policyMode,
            contain_arp: containArp,
            contain_dhcp: containDhcp
        },
        lifecycle: {
            auto_expire: autoExpire,
            duration_seconds: Number(durationSeconds) || 0
        }
    });

    const fetchActiveMitigations = async (silent = false) => {
        if (!silent) setLoadingMitigations(true);
        try {
            const response = await fetch('http://localhost:3001/api/odl/mitigations/active');
            if (!response.ok) throw new Error('Failed to fetch active mitigations');
            const data = await response.json();
            setActiveMitigations(Array.isArray(data.mitigations) ? data.mitigations : []);
        } catch (err) {
            console.error('Active mitigations fetch failed:', err);
        } finally {
            if (!silent) setLoadingMitigations(false);
        }
    };

    const fetchTopology = async () => {
        // Silent loading for polling if we already have data
        if (!topology) setLoading(true);
        setError(null);
        try {
            const response = await fetch('http://localhost:3001/api/odl/topology');
            if (!response.ok) throw new Error('Failed to fetch topology');
            const data = await response.json();
            setTopology(data);
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

            await fetch('http://localhost:3001/api/odl/scan', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });

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

        if (autoExpire && (!Number(durationSeconds) || Number(durationSeconds) <= 0)) {
            alert('Duration must be greater than 0 seconds when auto-expire is enabled');
            return;
        }

        try {
            setIsolateStatus('Sending command...');
            const payload = buildIsolationPayload();
            const response = await fetch('http://localhost:3001/api/odl/isolate', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(payload),
            });

            const raw = await response.text();
            let result = {};
            try {
                result = raw ? JSON.parse(raw) : {};
            } catch (parseError) {
                result = { error: raw || 'Unexpected non-JSON response from backend' };
            }
            if (response.ok) {
                setLastIsolationLocalization(result.localization || null);
                const localization = result.localization;
                const policySummary = `${policyMode} | ARP=${containArp ? 'on' : 'off'} | DHCP=${containDhcp ? 'on' : 'off'}`;
                if (localization?.node) {
                    const portText = localization.port ? `:${localization.port}` : '';
                    setIsolateStatus(`✅ Isolation command queued with ${policySummary}. Target ${localization.node}${portText} (${formatLocalizationStatus(localization.status)})`);
                } else {
                    setIsolateStatus(`✅ Isolation command queued with ${policySummary}.`);
                }
                fetchActiveMitigations(true);
            } else {
                setIsolateStatus(`❌ Error: ${result.error}`);
            }
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
            const response = await fetch('http://localhost:3001/api/odl/remove-isolation', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(selectedHost),
            });

            const raw = await response.text();
            let result = {};
            try {
                result = raw ? JSON.parse(raw) : {};
            } catch (parseError) {
                result = { error: raw || 'Unexpected non-JSON response from backend' };
            }
            if (response.ok) {
                const localization = result.localization || result.publish?.localization || null;
                setLastRemoveLocalization(localization);
                if (localization?.node) {
                    const portText = localization.port ? `:${localization.port}` : '';
                    setRemoveIsolationStatus(`✅ Remove isolation queued. Target ${localization.node}${portText} (${formatLocalizationStatus(localization.status)})`);
                } else {
                    setRemoveIsolationStatus('✅ Remove isolation/rollback command queued successfully!');
                }
                fetchActiveMitigations(true);
            } else {
                setRemoveIsolationStatus(`❌ Error: ${result.error}`);
            }
        } catch (err) {
            setRemoveIsolationStatus(`❌ Error: ${err.message}`);
        }
    };

    const handleClearMitigation = async (mitigationId) => {
        try {
            setMitigationStatus(`Clearing mitigation ${mitigationId}...`);
            const response = await fetch(`http://localhost:3001/api/odl/mitigations/${mitigationId}/clear`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    reason: 'manual-clear'
                })
            });

            const raw = await response.text();
            let result = {};
            try {
                result = raw ? JSON.parse(raw) : {};
            } catch (parseError) {
                result = { error: raw || 'Unexpected non-JSON response from backend' };
            }

            if (response.ok) {
                setMitigationStatus(`✅ Cleared mitigation ${mitigationId}`);
                fetchActiveMitigations(true);
            } else {
                setMitigationStatus(`❌ Clear failed: ${result.error}`);
            }
        } catch (err) {
            setMitigationStatus(`❌ Clear failed: ${err.message}`);
        }
    };

    const handleExtendMitigation = async (mitigationId) => {
        const extendSeconds = Number(extendSecondsById[mitigationId] || 300);
        if (!extendSeconds || extendSeconds <= 0) {
            alert('Extend seconds must be greater than 0');
            return;
        }

        try {
            setMitigationStatus(`Extending mitigation ${mitigationId}...`);
            const response = await fetch(`http://localhost:3001/api/odl/mitigations/${mitigationId}/extend`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ extend_seconds: extendSeconds })
            });

            const raw = await response.text();
            let result = {};
            try {
                result = raw ? JSON.parse(raw) : {};
            } catch (parseError) {
                result = { error: raw || 'Unexpected non-JSON response from backend' };
            }

            if (response.ok) {
                setMitigationStatus(`✅ Extended mitigation ${mitigationId} by ${extendSeconds}s`);
                fetchActiveMitigations(true);
            } else {
                setMitigationStatus(`❌ Extend failed: ${result.error}`);
            }
        } catch (err) {
            setMitigationStatus(`❌ Extend failed: ${err.message}`);
        }
    };

    useEffect(() => {
        // 1. Initial fetch
        fetchTopology();
        fetchActiveMitigations();

        // 2. Poll Topology every 5 seconds (Reads ODL state)
        const topologyInterval = setInterval(fetchTopology, 5000);

        // 3. Auto-Scan logic
        let scanInterval = null;
        if (isAutoScan) {
            // Trigger immediately when toggled on
            triggerScan();
            scanInterval = setInterval(triggerScan, 15000);
        }

        return () => {
            clearInterval(topologyInterval);
            if (scanInterval) clearInterval(scanInterval);
        };
    }, [isAutoScan]); // Re-run effect when isAutoScan changes

    useEffect(() => {
        const mitigationInterval = setInterval(() => {
            fetchActiveMitigations(true);
        }, 5000);

        return () => clearInterval(mitigationInterval);
    }, []);

    // Simple Topology Parser to extract hosts
    const getHosts = () => {
        if (!topology) return [];

        if (Array.isArray(topology.hosts)) {
            return topology.hosts.map((host) => ({
                ...host,
                localization: host.localization || {
                    status: 'partial',
                    confidence: 'low',
                    node: null,
                    port: null,
                    source_of_truth: 'odl.topology',
                    reason: 'Localization metadata missing'
                }
            }));
        }

        if (!topology['network-topology'] || !topology['network-topology'].topology) return [];

        const hosts = [];
        const topo = topology['network-topology'].topology[0];
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
                    localization: {
                        status: 'partial',
                        confidence: 'low',
                        node: null,
                        port: null,
                        source_of_truth: 'odl.topology',
                        reason: 'Legacy topology response without localization metadata'
                    }
                });
            }
        });
        return hosts;
    };

    const hosts = getHosts();
    const selectedHostLocalization = hosts.find(
        (host) => (selectedHost.ip && host.ip === selectedHost.ip) || (selectedHost.mac && host.mac === selectedHost.mac)
    )?.localization || null;

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
                                                <td>{host.attachment}</td>
                                                <td>
                                                    <span
                                                        style={{
                                                            ...LOCALIZATION_STYLE[host.localization?.status || 'partial'],
                                                            fontSize: '12px',
                                                            borderRadius: '12px',
                                                            padding: '4px 10px',
                                                            fontWeight: 600,
                                                            display: 'inline-block',
                                                            marginBottom: '4px'
                                                        }}
                                                    >
                                                        {formatLocalizationStatus(host.localization?.status || 'partial')}
                                                    </span>
                                                    <div style={{ fontSize: '12px', color: '#a0aec0' }}>
                                                        Node: {host.localization?.node || 'N/A'}
                                                        {host.localization?.port ? ` | Port: ${host.localization.port}` : ''}
                                                    </div>
                                                </td>
                                                <td>
                                                    <div style={{ display: 'flex', gap: '5px' }}>
                                                        <button
                                                            className="btn-danger-outline"
                                                            onClick={() => setSelectedHost({ ip: host.ip !== 'Unknown' ? host.ip : '', mac: host.mac })}
                                                        >
                                                            Isolate
                                                        </button>
                                                        <button
                                                            className="btn-success-outline"
                                                            onClick={() => setSelectedHost({ ip: host.ip !== 'Unknown' ? host.ip : '', mac: host.mac })}
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
                                    {JSON.stringify(topology, null, 2)}
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
                        <label>Policy Mode:</label>
                        <select
                            value={policyMode}
                            onChange={(e) => setPolicyMode(e.target.value)}
                            style={{ width: '100%', padding: '10px', borderRadius: '8px' }}
                        >
                            {POLICY_MODES.map((mode) => (
                                <option key={mode.value} value={mode.value}>{mode.label}</option>
                            ))}
                        </select>
                    </div>

                    <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap', marginTop: '10px' }}>
                        <label style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <input
                                type="checkbox"
                                checked={containArp}
                                onChange={(e) => setContainArp(e.target.checked)}
                            />
                            Contain ARP (default ON)
                        </label>

                        <label style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <input
                                type="checkbox"
                                checked={containDhcp}
                                onChange={(e) => setContainDhcp(e.target.checked)}
                            />
                            Contain DHCP (default ON)
                        </label>
                    </div>

                    <div style={{ marginTop: '15px', borderTop: '1px solid #444', paddingTop: '15px' }}>
                        <h3 style={{ margin: '0 0 10px 0' }}>Mitigation Lifecycle</h3>
                        <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
                            <label style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                <input
                                    type="checkbox"
                                    checked={autoExpire}
                                    onChange={(e) => setAutoExpire(e.target.checked)}
                                />
                                Auto-expire quarantine
                            </label>

                            <label style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                Duration (seconds):
                                <input
                                    type="number"
                                    min="1"
                                    value={durationSeconds}
                                    disabled={!autoExpire}
                                    onChange={(e) => setDurationSeconds(e.target.value)}
                                    style={{ width: '120px', padding: '6px', borderRadius: '6px' }}
                                />
                            </label>
                        </div>

                    </div>

                    <div style={{ marginTop: '12px', background: '#2d3748', padding: '12px', borderRadius: '8px', border: '1px solid #4a5568' }}>
                        <strong>Effective Policy Summary</strong>
                        <div style={{ marginTop: '6px', fontSize: '13px' }}>
                            Mode: {policyMode} | Bi-directional selectors: src+dst
                        </div>
                        <div style={{ fontSize: '13px' }}>
                            Containment: ARP {containArp ? 'ON' : 'OFF'}, DHCP {containDhcp ? 'ON' : 'OFF'}
                        </div>
                        <div style={{ fontSize: '13px' }}>
                            Lifecycle: {autoExpire ? `Auto-expire in ${durationSeconds}s` : 'No auto-expiry'}
                        </div>
                    </div>

                    <div style={{ display: 'flex', gap: '10px', marginTop: '10px' }}>
                        <button onClick={handleIsolate} className="btn-danger">
                            🚨 ISOLATE HOST
                        </button>

                        <button onClick={handleRemoveIsolation} className="btn-success">
                            ✅ REMOVE ISOLATION
                        </button>
                    </div>

                    {selectedHostLocalization && (
                        <div style={{ marginTop: '12px', fontSize: '13px', color: '#d1d5db' }}>
                            Isolation target: {selectedHostLocalization.node || 'openflow:1'}
                            {selectedHostLocalization.port ? `:${selectedHostLocalization.port}` : ''}
                            {' '}
                            <span
                                style={{
                                    ...LOCALIZATION_STYLE[selectedHostLocalization.status || 'partial'],
                                    borderRadius: '10px',
                                    padding: '2px 8px',
                                    marginLeft: '8px',
                                    fontSize: '11px',
                                    fontWeight: 700
                                }}
                            >
                                {formatLocalizationStatus(selectedHostLocalization.status || 'partial')}
                            </span>
                        </div>
                    )}

                    {isolateStatus && <p style={{ marginTop: '10px', fontWeight: 'bold' }}>{isolateStatus}</p>}
                    {lastIsolationLocalization?.fallback_reason && (
                        <p style={{ marginTop: '6px', color: '#f6ad55' }}>
                            Fallback reason: {lastIsolationLocalization.fallback_reason}
                        </p>
                    )}
                    {removeIsolationStatus && <p style={{ marginTop: '10px', fontWeight: 'bold' }}>{removeIsolationStatus}</p>}
                    {lastRemoveLocalization?.fallback_reason && (
                        <p style={{ marginTop: '6px', color: '#f6ad55' }}>
                            Fallback reason: {lastRemoveLocalization.fallback_reason}
                        </p>
                    )}
                </section>

                <section className="card" style={{ marginTop: '20px', borderLeft: '5px solid #2b6cb0' }}>
                    <h2>🧾 Active Mitigations</h2>
                    {loadingMitigations ? <p>Loading active mitigations...</p> : null}
                    {!loadingMitigations && activeMitigations.length === 0 ? <p>No active mitigations.</p> : null}

                    {activeMitigations.length > 0 && (
                        <table className="data-table">
                            <thead>
                                <tr>
                                    <th>Mitigation ID</th>
                                    <th>Target</th>
                                    <th>Policy</th>
                                    <th>Status</th>
                                    <th>Expiry</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {activeMitigations.map((item) => {
                                    const remainingSeconds = getRemainingSecondsFromExpiry(item.expires_at);
                                    return (
                                        <tr key={item.mitigation_id}>
                                            <td>{item.mitigation_id}</td>
                                            <td>
                                                {item.target?.ip || 'N/A'}
                                                <br />
                                                <span style={{ color: '#a0aec0', fontSize: '12px' }}>{item.target?.mac || 'N/A'}</span>
                                            </td>
                                            <td style={{ fontSize: '12px' }}>
                                                {item.policy?.mode || 'strict_bi_directional'}
                                                <br />
                                                ARP: {item.policy?.contain_arp ? 'ON' : 'OFF'} | DHCP: {item.policy?.contain_dhcp ? 'ON' : 'OFF'}
                                            </td>
                                            <td>{item.status}</td>
                                            <td>
                                                {item.expires_at ? formatSeconds(remainingSeconds) : 'No expiry'}
                                            </td>
                                            <td>
                                                <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                                                    <button
                                                        className="btn-success-outline"
                                                        onClick={() => handleClearMitigation(item.mitigation_id)}
                                                    >
                                                        Clear
                                                    </button>
                                                    <input
                                                        type="number"
                                                        min="30"
                                                        value={extendSecondsById[item.mitigation_id] || 300}
                                                        onChange={(e) => setExtendSecondsById({
                                                            ...extendSecondsById,
                                                            [item.mitigation_id]: e.target.value
                                                        })}
                                                        style={{ width: '90px', padding: '4px', borderRadius: '5px' }}
                                                    />
                                                    <button
                                                        className="btn-primary"
                                                        onClick={() => handleExtendMitigation(item.mitigation_id)}
                                                    >
                                                        Extend
                                                    </button>
                                                </div>
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    )}

                    {mitigationStatus && <p style={{ marginTop: '10px', fontWeight: 'bold' }}>{mitigationStatus}</p>}
                </section>
            </div>
        </div>
    );
}

export default NetworkControl;
