import React, { useState, useEffect, useRef } from 'react';
import io from 'socket.io-client';

const LOGS_STORAGE_KEY = 'webapp.logs.pageState.v1';

const DEFAULT_LOGS = {
  threatContextStore: [],
  moduleRegistry: [],
  workflowEngine: [],
  rabbitmq: []
};

const loadStoredLogsPageState = () => {
  try {
    const raw = sessionStorage.getItem(LOGS_STORAGE_KEY);
    if (!raw) {
      return {
        activeTab: 'threatContextStore',
        logs: DEFAULT_LOGS
      };
    }

    const parsed = JSON.parse(raw);
    const storedLogs = parsed?.logs && typeof parsed.logs === 'object' ? parsed.logs : {};

    return {
      activeTab: typeof parsed?.activeTab === 'string' ? parsed.activeTab : 'threatContextStore',
      logs: {
        threatContextStore: Array.isArray(storedLogs.threatContextStore) ? storedLogs.threatContextStore : [],
        moduleRegistry: Array.isArray(storedLogs.moduleRegistry) ? storedLogs.moduleRegistry : [],
        workflowEngine: Array.isArray(storedLogs.workflowEngine) ? storedLogs.workflowEngine : [],
        rabbitmq: Array.isArray(storedLogs.rabbitmq) ? storedLogs.rabbitmq : []
      }
    };
  } catch (error) {
    return {
      activeTab: 'threatContextStore',
      logs: DEFAULT_LOGS
    };
  }
};

const Logs = () => {
  const initialState = loadStoredLogsPageState();
  const [activeTab, setActiveTab] = useState(initialState.activeTab);
  const [connected, setConnected] = useState(false);
  const [logs, setLogs] = useState(initialState.logs);
  const logEndRef = useRef(null);
  const socket = useRef(null);
  
  useEffect(() => {
    console.log('Connecting to WebSocket...');
    socket.current = io('http://localhost:3001');
    
    socket.current.on('connect', () => {
      console.log('WebSocket connected!');
      setConnected(true);
    });
    
    socket.current.on('disconnect', () => {
      console.log('WebSocket disconnected');
      setConnected(false);
    });
    
    socket.current.on('process-log', (data) => {
      console.log('Received process-log:', data);
      setLogs(prev => ({
        ...prev,
        [data.process]: [...(prev[data.process] || []), { timestamp: data.timestamp, message: data.message }].slice(-1000)
      }));
    });
    
    socket.current.on('rabbitmq-log', (data) => {
      console.log('Received rabbitmq-log');
      const formatted = `[${data.queue}] ${JSON.stringify(data.message, null, 2)}`;
      setLogs(prev => ({
        ...prev,
        rabbitmq: [...(prev.rabbitmq || []), { timestamp: data.timestamp, message: formatted }].slice(-1000)
      }));
    });
    
    // Non-intrusive RabbitMQ stats
    socket.current.on('rabbitmq-stats', (data) => {
      const formatted = `[${data.queue}] total=${data.messages} ready=${data.messages_ready} unacked=${data.messages_unacknowledged} rate.in=${data.incoming_rate.toFixed?.(2) || data.incoming_rate}/s rate.out=${data.deliver_get_rate.toFixed?.(2) || data.deliver_get_rate}/s`;
      setLogs(prev => ({
        ...prev,
        rabbitmq: [...(prev.rabbitmq || []), { timestamp: data.timestamp, message: formatted }].slice(-1000)
      }));
    });
    
    return () => socket.current?.disconnect();
  }, []);
  
  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs, activeTab]);

  useEffect(() => {
    sessionStorage.setItem(LOGS_STORAGE_KEY, JSON.stringify({ activeTab, logs }));
  }, [activeTab, logs]);
  
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
