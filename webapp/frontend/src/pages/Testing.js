import React, { useState } from 'react';
import { processAPI } from '../services/api';

const Testing = () => {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState(null);
  
  const runTest = async () => {
    setRunning(true);
    setResult(null);
    try {
      const res = await processAPI.runTest();
      setResult(res.data);
    } catch (error) {
      setResult({
        success: false,
        message: error.response?.data?.error || error.message
      });
    }
    setRunning(false);
  };
  
  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">System Testing</h1>
        <p className="page-subtitle">Run TCSTester to validate the system</p>
      </div>
      
      <div className="card">
        <h3 className="card-title">ThreatContextStore Tester</h3>
        <p style={{color: 'var(--text-secondary)', marginBottom: '20px'}}>
          This will send 10 random security alerts to the system to test the complete pipeline.
        </p>
        
        <button
          className="button button-success"
          onClick={runTest}
          disabled={running}
        >
          {running ? (
            <>
              <span className="spinner" style={{marginRight: '8px'}}></span>
              Running Test...
            </>
          ) : (
            'Run Test'
          )}
        </button>
        
        {result && (
          <div style={{marginTop: '20px'}}>
            <div className={`alert alert-${result.success ? 'success' : 'error'}`}>
              {result.message}
            </div>
            
            {result.output && (
              <div>
                <h4 style={{marginTop: '20px', marginBottom: '12px'}}>Test Output:</h4>
                <div className="log-viewer" style={{height: '400px'}}>
                  <pre style={{margin: 0, whiteSpace: 'pre-wrap'}}>{result.output}</pre>
                </div>
              </div>
            )}
            
            {result.logFile && (
              <p style={{marginTop: '12px', fontSize: '13px', color: 'var(--text-secondary)'}}>
                Full log saved to: {result.logFile}
              </p>
            )}
          </div>
        )}
      </div>
      
      <div className="card">
        <h3 className="card-title">What This Test Does</h3>
        <ul style={{color: 'var(--text-secondary)', paddingLeft: '20px', lineHeight: '1.8'}}>
          <li>Generates 10 random security alerts with various severities</li>
          <li>Sends alerts to both <code style={{background: 'var(--bg-tertiary)', padding: '2px 6px', borderRadius: '4px'}}>alerts_queue</code> and <code style={{background: 'var(--bg-tertiary)', padding: '2px 6px', borderRadius: '4px'}}>workflow_queue</code></li>
          <li>ThreatContextStore stores alerts in PostgreSQL</li>
          <li>ModuleRegistry broadcasts to registered modules</li>
          <li>WorkflowEngine processes and executes matching workflows</li>
          <li>Check the Logs page to see real-time processing</li>
        </ul>
      </div>
    </div>
  );
};

export default Testing;
