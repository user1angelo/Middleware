#!/bin/bash

# Home Page
cat > frontend/src/pages/Home.js << 'EOF'
import React, { useState, useEffect } from 'react';
import { processAPI, moduleAPI } from '../services/api';

const Home = () => {
  const [processes, setProcesses] = useState({});
  const [modules, setModules] = useState({ total: 0, online: 0, offline: 0 });
  
  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 5000);
    return () => clearInterval(interval);
  }, []);
  
  const loadData = async () => {
    try {
      const [procRes, modRes] = await Promise.all([
        processAPI.getStatus(),
        moduleAPI.getHealth()
      ]);
      setProcesses(procRes.data);
      setModules(modRes.data);
    } catch (error) {
      console.error('Failed to load data:', error);
    }
  };
  
  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">System Overview</h1>
        <p className="page-subtitle">Middleware Dashboard</p>
      </div>
      
      <div className="grid grid-3">
        {Object.entries(processes).map(([key, proc]) => (
          <div key={key} className="card">
            <h3 className="card-title">{proc.name}</h3>
            <span className={`status-badge status-${proc.status}`}>
              {proc.status}
            </span>
            {proc.pid && <p style={{marginTop: '8px', color: 'var(--text-secondary)', fontSize: '13px'}}>PID: {proc.pid}</p>}
          </div>
        ))}
      </div>
      
      <div className="card">
        <h3 className="card-title">User-Defined Modules</h3>
        <div className="grid grid-3">
          <div>
            <div style={{fontSize: '24px', fontWeight: '600'}}>{modules.total}</div>
            <div style={{color: 'var(--text-secondary)', fontSize: '13px'}}>Total</div>
          </div>
          <div>
            <div style={{fontSize: '24px', fontWeight: '600', color: 'var(--success)'}}>{modules.online}</div>
            <div style={{color: 'var(--text-secondary)', fontSize: '13px'}}>Online</div>
          </div>
          <div>
            <div style={{fontSize: '24px', fontWeight: '600', color: 'var(--error)'}}>{modules.offline}</div>
            <div style={{color: 'var(--text-secondary)', fontSize: '13px'}}>Offline</div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default Home;
EOF

# Control Panel Page
cat > frontend/src/pages/ControlPanel.js << 'EOF'
import React, { useState, useEffect } from 'react';
import { processAPI } from '../services/api';

const ControlPanel = () => {
  const [processes, setProcesses] = useState({});
  const [loading, setLoading] = useState({});
  const [message, setMessage] = useState(null);
  
  useEffect(() => {
    loadProcesses();
    const interval = setInterval(loadProcesses, 3000);
    return () => clearInterval(interval);
  }, []);
  
  const loadProcesses = async () => {
    try {
      const res = await processAPI.getStatus();
      setProcesses(res.data);
    } catch (error) {
      console.error('Failed to load processes:', error);
    }
  };
  
  const handleStart = async (key) => {
    setLoading({...loading, [key]: true});
    setMessage(null);
    try {
      const res = await processAPI.start(key);
      setMessage({ type: 'success', text: res.data.message });
      await loadProcesses();
    } catch (error) {
      setMessage({ type: 'error', text: error.response?.data?.error || error.message });
    }
    setLoading({...loading, [key]: false});
  };
  
  const handleStop = async (key) => {
    setLoading({...loading, [key]: true});
    setMessage(null);
    try {
      const res = await processAPI.stop(key);
      setMessage({ type: 'success', text: res.data.message });
      await loadProcesses();
    } catch (error) {
      setMessage({ type: 'error', text: error.response?.data?.error || error.message });
    }
    setLoading({...loading, [key]: false});
  };
  
  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">Control Panel</h1>
        <p className="page-subtitle">Start and stop system processes</p>
      </div>
      
      {message && (
        <div className={`alert alert-${message.type}`}>
          {message.text}
        </div>
      )}
      
      {Object.entries(processes).map(([key, proc]) => (
        <div key={key} className="card">
          <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
            <div>
              <h3 className="card-title" style={{marginBottom: '4px'}}>{proc.name}</h3>
              <span className={`status-badge status-${proc.status}`}>
                {proc.status}
              </span>
              {proc.pid && <span style={{marginLeft: '12px', color: 'var(--text-secondary)', fontSize: '13px'}}>PID: {proc.pid}</span>}
            </div>
            <div className="button-group">
              <button
                className="button button-success"
                onClick={() => handleStart(key)}
                disabled={proc.status === 'running' || loading[key]}
              >
                {loading[key] ? <span className="spinner"></span> : 'Start'}
              </button>
              <button
                className="button button-error"
                onClick={() => handleStop(key)}
                disabled={proc.status !== 'running' || loading[key]}
              >
                {loading[key] ? <span className="spinner"></span> : 'Stop'}
              </button>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
};

export default ControlPanel;
EOF

# Logs Page
cat > frontend/src/pages/Logs.js << 'EOF'
import React, { useState, useEffect, useRef } from 'react';
import io from 'socket.io-client';

const Logs = () => {
  const [activeTab, setActiveTab] = useState('threatContextStore');
  const [logs, setLogs] = useState({
    threatContextStore: [],
    moduleRegistry: [],
    workflowEngine: [],
    rabbitmq: []
  });
  const logEndRef = useRef(null);
  const socket = useRef(null);
  
  useEffect(() => {
    socket.current = io('http://localhost:3001');
    
    socket.current.on('process-log', (data) => {
      setLogs(prev => ({
        ...prev,
        [data.process]: [...prev[data.process], { timestamp: data.timestamp, message: data.message }].slice(-1000)
      }));
    });
    
    socket.current.on('rabbitmq-log', (data) => {
      const formatted = `[${data.queue}] ${JSON.stringify(data.message, null, 2)}`;
      setLogs(prev => ({
        ...prev,
        rabbitmq: [...prev.rabbitmq, { timestamp: data.timestamp, message: formatted }].slice(-1000)
      }));
    });
    
    return () => socket.current?.disconnect();
  }, []);
  
  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs, activeTab]);
  
  const tabs = [
    { key: 'threatContextStore', label: 'ThreatContextStore' },
    { key: 'moduleRegistry', label: 'ModuleRegistry' },
    { key: 'workflowEngine', label: 'WorkflowEngine' },
    { key: 'rabbitmq', label: 'RabbitMQ' }
  ];
  
  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">System Logs</h1>
        <p className="page-subtitle">Real-time process and RabbitMQ logs</p>
      </div>
      
      <div className="card">
        <div className="tabs">
          {tabs.map(tab => (
            <button
              key={tab.key}
              className={`tab ${activeTab === tab.key ? 'active' : ''}`}
              onClick={() => setActiveTab(tab.key)}
            >
              {tab.label}
            </button>
          ))}
        </div>
        
        <div className="log-viewer">
          {logs[activeTab].map((log, i) => (
            <div key={i} className="log-line">
              <span style={{color: 'var(--text-muted)'}}>{new Date(log.timestamp).toLocaleTimeString()}</span>
              {' '}
              {log.message}
            </div>
          ))}
          {logs[activeTab].length === 0 && (
            <div style={{color: 'var(--text-secondary)'}}>No logs yet...</div>
          )}
          <div ref={logEndRef} />
        </div>
        
        <div style={{marginTop: '16px'}}>
          <button className="button button-small" onClick={() => setLogs({...logs, [activeTab]: []})}>
            Clear Logs
          </button>
        </div>
      </div>
    </div>
  );
};

export default Logs;
EOF

echo "Basic pages created. Creating workflow, config, modules, and testing pages..."

# The script continues in the next message due to length...
EOF

chmod +x webapp/create-pages.sh
