import React, { useState, useEffect } from 'react';
import '../App.css';

function NetworkControl() {
    const [topology, setTopology] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [selectedHost, setSelectedHost] = useState({ ip: '', mac: '' });
    const [isolateStatus, setIsolateStatus] = useState('');
    const [scanStatus, setScanStatus] = useState('');

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
            setScanStatus('Auto-Scanning...');
            await fetch('http://localhost:3001/api/odl/scan', { method: 'POST' });
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
            setIsolateStatus('Sending command...');
            const response = await fetch('http://localhost:3001/api/odl/isolate', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(selectedHost),
            });

            const result = await response.json();
            if (response.ok) {
                setIsolateStatus('✅ Isolation command sent successfully!');
            } else {
                setIsolateStatus(`❌ Error: ${result.error}`);
            }
        } catch (err) {
            setIsolateStatus(`❌ Error: ${err.message}`);
        }
    };

    useEffect(() => {
        // 1. Trigger initial scan
        triggerScan();
        // 2. Initial fetch
        fetchTopology();

        // 3. Poll Topology every 5 seconds (Reads ODL state)
        const topologyInterval = setInterval(fetchTopology, 5000);

        // 4. Trigger Active Scan every 15 seconds (Forces ODL to ping/discovery)
        const scanInterval = setInterval(triggerScan, 15000);

        return () => {
            clearInterval(topologyInterval);
            clearInterval(scanInterval);
        };
    }, []);

    // Simple Topology Parser to extract hosts
    const getHosts = () => {
        if (!topology || !topology['network-topology'] || !topology['network-topology'].topology) return [];

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
                    attachment: node['host-tracker-service:attachment-points']?.[0]?.['tp-id'] || 'Unknown'
                });
            }
        });
        return hosts;
    };

    const hosts = getHosts();

    return (
        <div className="App-page">
            <header className="App-header">
                <h1>Network Control Center</h1>
            </header>

            <div className="container" style={{ padding: '20px' }}>

                {/* Topology Section */}
                <section className="card">
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <h2>
                            🌐 Network Topology
                            <span className="status-badge status-running" style={{ fontSize: '0.6em', verticalAlign: 'middle', marginLeft: '10px' }}>
                                Continuous Scanning (15s)
                            </span>
                        </h2>
                        <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                            {scanStatus && <span style={{ fontSize: '0.9em', color: '#48bb78', fontStyle: 'italic' }}>{scanStatus}</span>}
                            <button onClick={() => { triggerScan(); }} disabled={loading} className="btn-primary">
                                Force Scan Now
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
                                                    <button
                                                        className="btn-danger-outline"
                                                        onClick={() => setSelectedHost({ ip: host.ip !== 'Unknown' ? host.ip : '', mac: host.mac })}
                                                    >
                                                        Select for Isolation
                                                    </button>
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

                    <button onClick={handleIsolate} className="btn-danger" style={{ marginTop: '10px' }}>
                        🚨 ISOLATE HOST
                    </button>

                    {isolateStatus && <p style={{ marginTop: '10px', fontWeight: 'bold' }}>{isolateStatus}</p>}
                </section>
            </div>
        </div>
    );
}

export default NetworkControl;
