import React from 'react';
import { Routes, Route, Link, useLocation } from 'react-router-dom';
import SdkOverview from './SdkOverview';
import SdkCoreApi from './SdkCoreApi';
import SdkPatterns from './SdkPatterns';
import SdkTroubleshooting from './SdkTroubleshooting';

const SdkDocs = () => {
  const location = useLocation();

  const subNav = [
    { path: '/sdk', label: 'Overview & Getting Started', exact: true },
    { path: '/sdk/core-api', label: 'Core API Reference' },
    { path: '/sdk/patterns', label: 'Patterns & Examples' },
    { path: '/sdk/troubleshooting', label: 'Common Mistakes & Troubleshooting' }
  ];

  return (
    <div className="sdk-docs-page">
      <div className="page-header">
        <h1 className="page-title">SDK Documentation</h1>
        <p className="page-subtitle">
          Learn how to build, test, and deploy user-defined modules using the SOAR SDK.
        </p>
      </div>

      <div className="card" style={{ marginBottom: '16px' }}>
        <div className="tab-nav">
          {subNav.map(item => {
            const isActive = item.exact
              ? location.pathname === item.path
              : location.pathname.startsWith(item.path);
            return (
              <Link
                key={item.path}
                to={item.path}
                className={`tab-link ${isActive ? 'active' : ''}`}
              >
                {item.label}
              </Link>
            );
          })}
        </div>
      </div>

      <Routes>
        <Route path="/" element={<SdkOverview />} />
        <Route path="/core-api" element={<SdkCoreApi />} />
        <Route path="/patterns" element={<SdkPatterns />} />
        <Route path="/troubleshooting" element={<SdkTroubleshooting />} />
      </Routes>
    </div>
  );
};

export default SdkDocs;
