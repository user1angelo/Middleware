import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import Layout from './components/Layout';
import Home from './pages/Home';
import ControlPanel from './pages/ControlPanel';
import Logs from './pages/Logs';
import Workflows from './pages/Workflows';
import Configuration from './pages/Configuration';
import Modules from './pages/Modules';
import Testing from './pages/Testing';
import SdkDocs from './pages/SdkDocs';
import './App.css';

function App() {
  return (
    <Router>
      <Layout>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/control" element={<ControlPanel />} />
          <Route path="/logs" element={<Logs />} />
          <Route path="/workflows" element={<Workflows />} />
          <Route path="/config" element={<Configuration />} />
          <Route path="/modules" element={<Modules />} />
          <Route path="/test" element={<Testing />} />
          <Route path="/sdk/*" element={<SdkDocs />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Layout>
    </Router>
  );
}

export default App;
