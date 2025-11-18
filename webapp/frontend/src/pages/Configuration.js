import React, { useState, useEffect } from 'react';
import { configAPI, udmConfigAPI } from '../services/api';

const programs = [
  { key: 'threatContextStore', label: 'ThreatContextStore' },
  { key: 'moduleRegistry', label: 'ModuleRegistry' },
  { key: 'workflowEngine', label: 'WorkflowEngine' }
];

const Configuration = () => {
  const [activeTab, setActiveTab] = useState('threatContextStore');
  const [configs, setConfigs] = useState({});
  const [editing, setEditing] = useState({});

  // UDM configs
  const [udmList, setUdmList] = useState([]);
  const [activeUdmId, setActiveUdmId] = useState(null);
  const [udmConfigs, setUdmConfigs] = useState({});
  const [udmEditing, setUdmEditing] = useState({});

  const [message, setMessage] = useState(null);
  
  useEffect(() => {
    programs.forEach(p => loadConfig(p.key));
    loadUdmConfigList();
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

  const loadUdmConfigList = async () => {
    try {
      const res = await udmConfigAPI.list();
      const list = res.data.configs || [];
      setUdmList(list);
      if (list.length > 0 && !activeUdmId) {
        setActiveUdmId(list[0].id);
        // Lazy-load first config content
        loadUdmConfig(list[0].id);
      }
    } catch (error) {
      console.error('Failed to load UDM config list:', error);
    }
  };

  const loadUdmConfig = async (id) => {
    try {
      const res = await udmConfigAPI.get(id);
      setUdmConfigs(prev => ({
        ...prev,
        [id]: res.data.content
      }));
      setUdmEditing(prev => ({
        ...prev,
        [id]: res.data.content
      }));
    } catch (error) {
      console.error(`Failed to load UDM config for ${id}:`, error);
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

      <div className="card" style={{ marginTop: '24px' }}>
        <h3 className="card-title">User-Defined Module Configs</h3>
        <p className="page-subtitle" style={{ marginBottom: '12px' }}>
          Edit configuration for standalone UDMs (PRTG, OpenDaylight, etc.).
        </p>

        {udmList.length === 0 ? (
          <div style={{ color: 'var(--text-secondary)', padding: '16px' }}>
            No UDM config files found under <code>user-defined-modules/config</code>.
          </div>
        ) : (
          <>
            <div className="tabs" style={{ marginBottom: '8px' }}>
              {udmList.map(cfg => (
                <button
                  key={cfg.id}
                  className={`tab ${activeUdmId === cfg.id ? 'active' : ''}`}
                  onClick={() => {
                    setActiveUdmId(cfg.id);
                    if (!udmConfigs[cfg.id]) {
                      loadUdmConfig(cfg.id);
                    }
                  }}
                >
                  {cfg.name}
                </button>
              ))}
            </div>

            <textarea
              className="textarea"
              value={(activeUdmId && udmEditing[activeUdmId]) || ''}
              onChange={(e) => setUdmEditing({
                ...udmEditing,
                [activeUdmId]: e.target.value
              })}
              placeholder={activeUdmId ? 'Loading UDM configuration...' : 'Select a UDM config'}
            />

            <div style={{ marginTop: '16px' }} className="button-group">
              <button
                className="button button-success"
                onClick={async () => {
                  if (!activeUdmId) return;
                  setMessage(null);
                  try {
                    await udmConfigAPI.update(activeUdmId, udmEditing[activeUdmId] || '');
                    setUdmConfigs(prev => ({
                      ...prev,
                      [activeUdmId]: udmEditing[activeUdmId]
                    }));
                    setMessage({ type: 'success', text: `UDM configuration saved for ${activeUdmId}` });
                  } catch (error) {
                    setMessage({ type: 'error', text: error.response?.data?.error || error.message });
                  }
                }}
                disabled={!activeUdmId || udmEditing[activeUdmId] === udmConfigs[activeUdmId]}
              >
                Save UDM Config
              </button>
              <button
                className="button"
                onClick={() => {
                  if (!activeUdmId) return;
                  setUdmEditing(prev => ({
                    ...prev,
                    [activeUdmId]: udmConfigs[activeUdmId]
                  }));
                }}
                disabled={!activeUdmId || udmEditing[activeUdmId] === udmConfigs[activeUdmId]}
              >
                Reset
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default Configuration;
