import React, { useState, useEffect } from 'react';
import { moduleAPI } from '../services/api';

const Modules = () => {
  const [modules, setModules] = useState([]);
  const [stats, setStats] = useState({ total: 0, online: 0, offline: 0 });
  const [loading, setLoading] = useState({});
  const [message, setMessage] = useState(null);
  
  const loadModules = async () => {
    try {
      const res = await moduleAPI.getHealth();
      setModules(res.data.modules);
      setStats({
        total: res.data.total,
        online: res.data.online,
        offline: res.data.offline
      });
    } catch (error) {
      console.error('Failed to load modules:', error);
    }
  };

  useEffect(() => {
    loadModules();
    const interval = setInterval(loadModules, 5000);
    return () => clearInterval(interval);
  }, []);

  const handleStart = async (module) => {
    const id = module.config_id || module.module_id;
    if (!id) return;

    setLoading(prev => ({ ...prev, [id]: true }));
    setMessage(null);

    try {
      const res = await moduleAPI.start(id);
      setMessage({ type: 'success', text: res.data.message });
    } catch (error) {
      setMessage({ type: 'error', text: error.response?.data?.error || error.message });
    }

    setLoading(prev => ({ ...prev, [id]: false }));
  };
  
  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">User-Defined Modules</h1>
        <p className="page-subtitle">Health status of registered modules</p>
      </div>
      
      {message && (
        <div className={`alert alert-${message.type}`}>
          {message.text}
        </div>
      )}
      
      <div className="grid grid-3">
        <div className="card">
          <div style={{fontSize: '32px', fontWeight: '600', marginBottom: '8px'}}>{stats.total}</div>
          <div style={{color: 'var(--text-secondary)'}}>Total Modules</div>
        </div>
        <div className="card">
          <div style={{fontSize: '32px', fontWeight: '600', color: 'var(--success)', marginBottom: '8px'}}>{stats.online}</div>
          <div style={{color: 'var(--text-secondary)'}}>Online</div>
        </div>
        <div className="card">
          <div style={{fontSize: '32px', fontWeight: '600', color: 'var(--error)', marginBottom: '8px'}}>{stats.offline}</div>
          <div style={{color: 'var(--text-secondary)'}}>Offline</div>
        </div>
      </div>
      
      <div className="card">
        <h3 className="card-title">Registered Modules</h3>
        {modules.length === 0 ? (
          <div style={{color: 'var(--text-secondary)', textAlign: 'center', padding: '32px'}}>
            No modules registered yet
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Module Name</th>
                <th>Type</th>
                <th>Status</th>
                <th>Last Heartbeat</th>
                <th>Capabilities</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {modules.map(module => {
                const id = module.config_id || module.module_id;
                const isLoading = id && loading[id];
                const isOnline = module.status === 'online';

                return (
                  <tr key={module.module_id || id}>
                    <td>
                      <div style={{fontWeight: '500'}}>{module.module_name}</div>
                      <div style={{fontSize: '12px', color: 'var(--text-muted)'}}>{module.module_id}</div>
                    </td>
                    <td>{module.module_type}</td>
                    <td>
                      <span className={`status-badge status-${module.status}`}>
                        {module.status}
                      </span>
                    </td>
                    <td>
                      {module.last_heartbeat ? (
                        <>
                          <div>{new Date(module.last_heartbeat).toLocaleString()}</div>
                          <div style={{fontSize: '12px', color: 'var(--text-muted)'}}>
                            ({module.secondsSinceHeartbeat}s ago)
                          </div>
                        </>
                      ) : (
                        <span style={{color: 'var(--text-muted)'}}>Never</span>
                      )}
                    </td>
                    <td>
                      {Array.isArray(module.capabilities) ? (
                        <div style={{display: 'flex', gap: '4px', flexWrap: 'wrap'}}>
                          {module.capabilities.map((cap, i) => (
                            <span
                              key={i}
                              style={{
                                padding: '2px 8px',
                                background: 'var(--bg-tertiary)',
                                borderRadius: '4px',
                                fontSize: '12px'
                              }}
                            >
                              {cap}
                            </span>
                          ))}
                        </div>
                      ) : (
                        <span style={{color: 'var(--text-muted)'}}>None</span>
                      )}
                    </td>
                    <td>
                      {id ? (
                        <button
                          className="button button-success"
                          onClick={() => handleStart(module)}
                          disabled={isLoading || isOnline}
                        >
                          {isLoading ? <span className="spinner"></span> : 'Start'}
                        </button>
                      ) : (
                        <span style={{color: 'var(--text-muted)'}}>N/A</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};

export default Modules;
