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
