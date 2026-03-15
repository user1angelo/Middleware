import React, { useEffect, useRef } from 'react';

const Logs = ({ activeTab, setActiveTab, connected, logs, setLogs }) => {
  const logEndRef = useRef(null);
  
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
        <p className="page-subtitle">
          Real-time process and RabbitMQ logs
          {' '}
          <span className={`status-badge status-${connected ? 'online' : 'offline'}`}>
            {connected ? 'Connected' : 'Disconnected'}
          </span>
        </p>
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
          {(logs[activeTab] || []).map((log, i) => (
            <div key={i} className="log-line">
              <span style={{color: 'var(--text-muted)'}}>{new Date(log.timestamp).toLocaleTimeString()}</span>
              {' '}
              {log.message}
            </div>
          ))}
          {(!logs[activeTab] || logs[activeTab].length === 0) && (
            <div style={{color: 'var(--text-secondary)'}}>No logs yet...</div>
          )}
          <div ref={logEndRef} />
        </div>
        
        <div style={{marginTop: '16px'}}>
          <button
            className="button button-small"
            onClick={() => setLogs(prev => ({ ...prev, [activeTab]: [] }))}
          >
            Clear Logs
          </button>
        </div>
      </div>
    </div>
  );
};

export default Logs;
