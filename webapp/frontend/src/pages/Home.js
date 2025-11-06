import React, { useState, useEffect, useRef } from 'react';
import { processAPI, moduleAPI } from '../services/api';
import io from 'socket.io-client';

const Home = () => {
  const [processes, setProcesses] = useState({});
  const [modules, setModules] = useState({ total: 0, online: 0, offline: 0 });
  const [logs, setLogs] = useState([]);
  const logViewerRef = useRef(null);
  const socketRef = useRef(null);
  
  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 5000);
    
    // Connect to WebSocket for real-time logs
    socketRef.current = io('http://localhost:3001');
    
    socketRef.current.on('process-log', (log) => {
      addLog(`[${log.process}] ${log.message}`);
    });
    
    socketRef.current.on('rabbitmq-log', (log) => {
      const msgType = log.message.message_type || 'unknown';
      const eventId = log.message.event_id || 'N/A';
      addLog(`[${log.queue}] ${msgType} | event_id: ${eventId}`);
    });
    
    return () => {
      clearInterval(interval);
      if (socketRef.current) {
        socketRef.current.disconnect();
      }
    };
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
  
  const addLog = (message) => {
    const timestamp = new Date().toLocaleTimeString();
    setLogs(prev => {
      const newLogs = [...prev, `[${timestamp}] ${message}`];
      // Keep only last 100 logs
      return newLogs.slice(-100);
    });
    
    // Auto-scroll to bottom
    setTimeout(() => {
      if (logViewerRef.current) {
        logViewerRef.current.scrollTop = logViewerRef.current.scrollHeight;
      }
    }, 10);
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
      
      <div className="card">
        <h3 className="card-title">Real-Time Logs</h3>
        <div ref={logViewerRef} className="log-viewer">
          {logs.length === 0 ? (
            <div style={{color: 'var(--text-muted)'}}>Waiting for logs...</div>
          ) : (
            logs.map((log, index) => (
              <div key={index} className="log-line">{log}</div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

export default Home;
