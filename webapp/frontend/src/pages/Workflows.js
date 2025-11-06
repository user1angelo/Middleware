import React, { useState, useEffect } from 'react';
import { workflowAPI } from '../services/api';

const Workflows = () => {
  const [workflows, setWorkflows] = useState({});
  const [selected, setSelected] = useState(null);
  const [content, setContent] = useState('');
  const [editing, setEditing] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [message, setMessage] = useState(null);
  
  useEffect(() => {
    loadWorkflows();
  }, []);
  
  const loadWorkflows = async () => {
    try {
      const res = await workflowAPI.list();
      setWorkflows(res.data.workflows);
    } catch (error) {
      console.error('Failed to load workflows:', error);
    }
  };
  
  const handleSelect = async (category, workflow) => {
    try {
      const res = await workflowAPI.get(category, workflow.name);
      setSelected({ category, name: workflow.name });
      setContent(res.data.content);
      setEditing(false);
      setCreating(false);
    } catch (error) {
      setMessage({ type: 'error', text: error.response?.data?.error || error.message });
    }
  };
  
  const handleSave = async () => {
    try {
      if (creating) {
        await workflowAPI.create(selected.category, newName, content);
        setMessage({ type: 'success', text: 'Workflow created!' });
        setCreating(false);
      } else {
        await workflowAPI.update(selected.category, selected.name, content);
        setMessage({ type: 'success', text: 'Workflow updated!' });
      }
      setEditing(false);
      await loadWorkflows();
    } catch (error) {
      setMessage({ type: 'error', text: error.response?.data?.error || error.message });
    }
  };
  
  const handleDelete = async () => {
    if (!window.confirm(`Delete ${selected.name}?`)) return;
    try {
      await workflowAPI.delete(selected.category, selected.name);
      setMessage({ type: 'success', text: 'Workflow deleted!' });
      setSelected(null);
      setContent('');
      await loadWorkflows();
    } catch (error) {
      setMessage({ type: 'error', text: error.response?.data?.error || error.message });
    }
  };
  
  const handleNew = (category) => {
    setSelected({ category });
    setContent('');
    setNewName('');
    setCreating(true);
    setEditing(true);
  };
  
  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">Workflows</h1>
        <p className="page-subtitle">Manage workflow definitions</p>
      </div>
      
      {message && (
        <div className={`alert alert-${message.type}`}>
          {message.text}
        </div>
      )}
      
      <div className="grid grid-2">
        <div className="card">
          <h3 className="card-title">Available Workflows</h3>
          {Object.entries(workflows).map(([category, items]) => (
            <div key={category} style={{marginBottom: '20px'}}>
              <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px'}}>
                <h4 style={{color: 'var(--text-secondary)', fontSize: '14px', fontWeight: '500'}}>{category}</h4>
                <button className="button button-small" onClick={() => handleNew(category)}>New</button>
              </div>
              {items.map(wf => (
                <div
                  key={wf.name}
                  style={{
                    padding: '8px 12px',
                    cursor: 'pointer',
                    borderRadius: '4px',
                    marginBottom: '4px',
                    background: selected?.name === wf.name ? 'var(--bg-tertiary)' : 'transparent'
                  }}
                  onClick={() => handleSelect(category, wf)}
                >
                  {wf.name}
                </div>
              ))}
            </div>
          ))}
        </div>
        
        <div className="card">
          <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px'}}>
            <h3 className="card-title" style={{marginBottom: 0}}>
              {creating ? 'New Workflow' : selected ? selected.name : 'No workflow selected'}
            </h3>
            {selected && (
              <div className="button-group">
                {editing ? (
                  <>
                    <button className="button button-success button-small" onClick={handleSave}>Save</button>
                    <button className="button button-small" onClick={() => setEditing(false)}>Cancel</button>
                  </>
                ) : (
                  <>
                    <button className="button button-small" onClick={() => setEditing(true)}>Edit</button>
                    <button className="button button-error button-small" onClick={handleDelete}>Delete</button>
                  </>
                )}
              </div>
            )}
          </div>
          
          {creating && (
            <input
              type="text"
              className="input"
              placeholder="Workflow name (e.g., my-workflow.yml)"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              style={{marginBottom: '16px'}}
            />
          )}
          
          {selected && (
            <textarea
              className="textarea"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              readOnly={!editing}
              placeholder="Enter YAML content..."
            />
          )}
        </div>
      </div>
    </div>
  );
};

export default Workflows;
