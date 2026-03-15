import React, { useEffect, useState } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import io from 'socket.io-client';
import Layout from './components/Layout';
import Home from './pages/Home';
import ControlPanel from './pages/ControlPanel';
import Logs from './pages/Logs';
import Workflows from './pages/Workflows';
import Configuration from './pages/Configuration';
import Modules from './pages/Modules';
import Testing from './pages/Testing';
import SdkDocs from './pages/SdkDocs';
import NetworkControl from './pages/NetworkControl';
import './App.css';

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

function App() {
  const initialState = loadStoredLogsPageState();
  const [activeTab, setActiveTab] = useState(initialState.activeTab);
  const [logs, setLogs] = useState(initialState.logs);
  const [logsConnected, setLogsConnected] = useState(false);

  useEffect(() => {
    const socket = io('http://localhost:3001');

    socket.on('connect', () => {
      setLogsConnected(true);
    });

    socket.on('disconnect', () => {
      setLogsConnected(false);
    });

    socket.on('process-log', (data) => {
      setLogs((prev) => ({
        ...prev,
        [data.process]: [...(prev[data.process] || []), { timestamp: data.timestamp, message: data.message }].slice(-1000)
      }));
    });

    socket.on('rabbitmq-log', (data) => {
      const formatted = `[${data.queue}] ${JSON.stringify(data.message, null, 2)}`;
      setLogs((prev) => ({
        ...prev,
        rabbitmq: [...(prev.rabbitmq || []), { timestamp: data.timestamp, message: formatted }].slice(-1000)
      }));
    });

    socket.on('rabbitmq-stats', (data) => {
      const formatted = `[${data.queue}] total=${data.messages} ready=${data.messages_ready} unacked=${data.messages_unacknowledged} rate.in=${data.incoming_rate.toFixed?.(2) || data.incoming_rate}/s rate.out=${data.deliver_get_rate.toFixed?.(2) || data.deliver_get_rate}/s`;
      setLogs((prev) => ({
        ...prev,
        rabbitmq: [...(prev.rabbitmq || []), { timestamp: data.timestamp, message: formatted }].slice(-1000)
      }));
    });

    return () => {
      socket.disconnect();
    };
  }, []);

  useEffect(() => {
    sessionStorage.setItem(LOGS_STORAGE_KEY, JSON.stringify({ activeTab, logs }));
  }, [activeTab, logs]);

  return (
    <Router>
      <Layout>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/control" element={<ControlPanel />} />
          <Route
            path="/logs"
            element={(
              <Logs
                activeTab={activeTab}
                setActiveTab={setActiveTab}
                logs={logs}
                setLogs={setLogs}
                connected={logsConnected}
              />
            )}
          />
          <Route path="/workflows" element={<Workflows />} />
          <Route path="/config" element={<Configuration />} />
          <Route path="/modules" element={<Modules />} />
          <Route path="/network" element={<NetworkControl />} />
          <Route path="/test" element={<Testing />} />
          <Route path="/sdk/*" element={<SdkDocs />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Layout>
    </Router>
  );
}

export default App;
