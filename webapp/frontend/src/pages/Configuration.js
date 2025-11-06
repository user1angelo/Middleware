import React, { useState, useEffect } from 'react';
import { configAPI } from '../services/api';

const programs = [
  { key: 'threatContextStore', label: 'ThreatContextStore' },
  { key: 'moduleRegistry', label: 'ModuleRegistry' },
  { key: 'workflowEngine', label: 'WorkflowEngine' }
];

const Configuration = () => {
  const [activeTab, setActiveTab] = useState('threatContextStore');
  const [configs, setConfigs] = useState({});
  const [editing, setEditing] = useState({});
  const [message, setMessage] = useState(null);
  
  useEffect(() => {
    programs.forEach(p => loadConfig(p.key));
  }, []);
  
  const loadConfig = async (programKey) => {
    try {
      const res = await configAPI.get(programKey);
      setConfigs(prev => ({
        ...prev,
        [programKey]: res.data.content
      }));
      setEditing(prev => ({
        ...prev,
        [programKey]: res.data.content
      }));
    } catch (error) {
      console.error(`Failed to load config for ${programKey}:`, error);
    }
  };
  
  const handleSave = async (programKey) => {
    setMessage(null);
    try {
      await configAPI.update(programKey, editing[programKey]);
      setConfigs(prev => ({
        ...prev,
        [programKey]: editing[programKey]
      }));
      setMessage({ type: 'success', text: `Configuration saved for ${programKey}` });
    } catch (error) {
      setMessage({ type: 'error', text: error.response?.data?.error || error.message });
    }
  };
  
  const handleReset = (programKey) => {
    setEditing(prev => ({
      ...prev,
      [programKey]: configs[programKey]
    }));
  };
  
  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">Configuration</h1>
        <p className="page-subtitle">Edit program configuration files</p>
      </div>
      
      {message && (
        <div className={`alert alert-${message.type}`}>
          {message.text}
        </div>
      )}
      
      <div className="card">
        <div className="tabs">
          {programs.map(prog => (
            <button
              key={prog.key}
              className={`tab ${activeTab === prog.key ? 'active' : ''}`}
              onClick={() => setActiveTab(prog.key)}
            >
              {prog.label}
            </button>
          ))}
        </div>
        
        <textarea
          className="textarea"
          value={editing[activeTab] || ''}
          onChange={(e) => setEditing({...editing, [activeTab]: e.target.value})}
          placeholder="Loading configuration..."
        />
        
        <div style={{marginTop: '16px'}} className="button-group">
          <button
            className="button button-success"
            onClick={() => handleSave(activeTab)}
            disabled={editing[activeTab] === configs[activeTab]}
          >
            Save Changes
          </button>
          <button
            className="button"
            onClick={() => handleReset(activeTab)}
            disabled={editing[activeTab] === configs[activeTab]}
          >
            Reset
          </button>
        </div>
      </div>
    </div>
  );
};

export default Configuration;
