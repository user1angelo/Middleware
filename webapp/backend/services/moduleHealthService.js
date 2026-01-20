const fs = require('fs');
const path = require('path');

// Filesystem-based discovery: user-defined-modules JARs are the
// source of truth for which module IDs exist.
function getFilesystemModules() {
  const middlewareRoot = process.env.MIDDLEWARE_ROOT || path.join(__dirname, '../../../');
  const modulesRoot = process.env.MODULES_ROOT || path.join(middlewareRoot, 'user-defined-modules');

  const modules = [];

  if (!fs.existsSync(modulesRoot) || !fs.statSync(modulesRoot).isDirectory()) {
    console.warn('[moduleHealthService] No user-defined-modules directory at', modulesRoot);
    return modules;
  }

  const configDir = path.join(modulesRoot, 'config');
  const jarFiles = fs.readdirSync(modulesRoot).filter(f => f.endsWith('.jar'));

  for (const jarName of jarFiles) {
    const id = jarName.replace(/\.jar$/, '');
    const jarPath = path.join(modulesRoot, jarName);

    let configPath = null;
    if (fs.existsSync(configDir) && fs.statSync(configDir).isDirectory()) {
      const candidate = path.join(configDir, `${id}.properties`);
      if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
        configPath = candidate;
      }
    }

    modules.push({
      module_id: id,
      module_name: id,
      module_type: 'generic_udm',
      capabilities: [],
      command_queue: `${id}_commands_queue`,
      registered_at: null,
      last_heartbeat: null,
      status: 'offline',
      metadata: {
        source: 'jar',
        jar_path: jarPath,
        ...(configPath ? { config_path: configPath } : {})
      },
      seconds_since_heartbeat: null
    });
  }

  return modules;
}

// Get health status of all registered modules
async function getModuleHealth() {
  const fsModules = getFilesystemModules();

  // Treat JAR modules as "alive" when the ModuleRegistry process is
  // running. The registry (and its SDK host) is the source of truth for
  // whether SDK-based modules like OpenDaylightModule are up.
  let registryRunning = false;
  try {
    const processManager = require('./processManager');
    const status = processManager.getAllStatus();
    if (status && status.moduleRegistry && status.moduleRegistry.status === 'running') {
      registryRunning = true;
    }
  } catch (e) {
    console.warn('[moduleHealthService] Could not determine ModuleRegistry status:', e.message);
  }

  const modules = fsModules.map(m => ({
    ...m,
    config_id: m.module_id,
    status: registryRunning ? 'online' : 'offline',
    isHealthy: registryRunning,
    secondsSinceHeartbeat: null
  }));

  return {
    success: true,
    modules,
    total: modules.length,
    online: modules.filter(m => m.status === 'online').length,
    offline: modules.filter(m => m.status === 'offline').length
  };
}

// Get a specific module's details (filesystem + registry status only)
async function getModuleDetails(moduleId) {
  const fsModules = getFilesystemModules();
  const base = fsModules.find(m => m.module_id === moduleId);
  if (!base) {
    throw new Error(`Module not found on filesystem: ${moduleId}`);
  }

  let registryRunning = false;
  try {
    const processManager = require('./processManager');
    const status = processManager.getAllStatus();
    if (status && status.moduleRegistry && status.moduleRegistry.status === 'running') {
      registryRunning = true;
    }
  } catch (e) {
    console.warn('[moduleHealthService] Could not determine ModuleRegistry status for details:', e.message);
  }

  return {
    success: true,
    module: {
      ...base,
      config_id: base.module_id,
      status: registryRunning ? 'online' : 'offline',
      isHealthy: registryRunning,
      secondsSinceHeartbeat: null
    }
  };
}

module.exports = {
  getModuleHealth,
  getModuleDetails
};
