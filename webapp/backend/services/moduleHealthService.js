const fs = require('fs');
const path = require('path');

// Optional: only used if `pg` is actually installed (it's declared in package.json but
// require() is still wrapped defensively so a missing/broken install degrades to
// filesystem-only behavior instead of crashing this whole service on load).
let Pool = null;
try {
  ({ Pool } = require('pg'));
} catch (e) {
  console.warn('[moduleHealthService] "pg" package not available - real module capabilities will not be shown:', e.message);
}

let pool = null;
function getPool() {
  if (!Pool) {
    return null;
  }
  if (!pool) {
    pool = new Pool({
      host: process.env.DB_HOST || 'localhost',
      port: parseInt(process.env.DB_PORT || '5432', 10),
      database: process.env.DB_NAME || 'middleware',
      user: process.env.DB_USER || 'postgres',
      password: process.env.DB_PASSWORD || 'postgres',
      max: 2,
      idleTimeoutMillis: 5000,
      connectionTimeoutMillis: 2000
    });
    pool.on('error', (err) => {
      console.warn('[moduleHealthService] Postgres pool error (non-fatal):', err.message);
    });
  }
  return pool;
}

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

/**
 * Queries the live `registered_modules` table (the same one `ModuleRegistry.registerModule`
 * writes to on the Java side) for each module's REAL capabilities/status/heartbeat, keyed by
 * module_id.
 *
 * Previously this data was never actually read anywhere in the webapp backend despite the
 * frontend (`Modules.js`) already rendering a capabilities column - `capabilities` was always
 * hardcoded to `[]` here. See SDK_USABILITY_AUDIT.md, Penetrability dimension.
 *
 * Best-effort: returns an empty Map (not a thrown error) if Postgres is unreachable, matching
 * how the Java side already treats Postgres as optional/best-effort persistence.
 */
async function getRegisteredModulesFromDb() {
  const p = getPool();
  if (!p) {
    return new Map();
  }

  try {
    const result = await p.query(
      'SELECT module_id, module_name, module_type, capabilities, command_queue, status, last_heartbeat, metadata FROM registered_modules'
    );

    const byModuleId = new Map();
    for (const row of result.rows) {
      byModuleId.set(row.module_id, {
        module_name: row.module_name,
        module_type: row.module_type,
        capabilities: Array.isArray(row.capabilities) ? row.capabilities : [],
        command_queue: row.command_queue,
        status: row.status,
        last_heartbeat: row.last_heartbeat,
        metadata: row.metadata || {}
      });
    }
    return byModuleId;
  } catch (e) {
    console.warn('[moduleHealthService] Could not query registered_modules (falling back to filesystem-only data):', e.message);
    return new Map();
  }
}

function secondsSince(timestamp) {
  if (!timestamp) {
    return null;
  }
  return Math.max(0, Math.floor((Date.now() - new Date(timestamp).getTime()) / 1000));
}

// Get health status of all registered modules
async function getModuleHealth() {
  const fsModules = getFilesystemModules();
  const dbModules = await getRegisteredModulesFromDb();

  // Treat JAR modules as "alive" when the ModuleRegistry process is
  // running, as a fallback for modules the DB doesn't (yet) have a record for.
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

  const modules = fsModules.map(m => {
    const dbRecord = dbModules.get(m.module_id);
    if (dbRecord) {
      return {
        ...m,
        config_id: m.module_id,
        module_name: dbRecord.module_name || m.module_name,
        module_type: dbRecord.module_type || m.module_type,
        capabilities: dbRecord.capabilities,
        command_queue: dbRecord.command_queue || m.command_queue,
        status: dbRecord.status,
        last_heartbeat: dbRecord.last_heartbeat,
        isHealthy: dbRecord.status === 'online',
        secondsSinceHeartbeat: secondsSince(dbRecord.last_heartbeat)
      };
    }
    return {
      ...m,
      config_id: m.module_id,
      status: registryRunning ? 'online' : 'offline',
      isHealthy: registryRunning,
      secondsSinceHeartbeat: null
    };
  });

  // Also surface any DB-registered module not discovered on the filesystem at all - this is how
  // embedded SDK modules (OpenDaylightModule, NotificationModule, ...) become visible, since
  // they aren't separate JARs the filesystem scan above can find.
  for (const [moduleId, dbRecord] of dbModules.entries()) {
    if (!modules.some(m => m.module_id === moduleId)) {
      modules.push({
        module_id: moduleId,
        module_name: dbRecord.module_name,
        module_type: dbRecord.module_type,
        capabilities: dbRecord.capabilities,
        command_queue: dbRecord.command_queue,
        registered_at: null,
        last_heartbeat: dbRecord.last_heartbeat,
        status: dbRecord.status,
        metadata: dbRecord.metadata,
        config_id: moduleId,
        isHealthy: dbRecord.status === 'online',
        secondsSinceHeartbeat: secondsSince(dbRecord.last_heartbeat)
      });
    }
  }

  return {
    success: true,
    modules,
    total: modules.length,
    online: modules.filter(m => m.status === 'online').length,
    offline: modules.filter(m => m.status === 'offline').length
  };
}

// Get a specific module's details (filesystem + registry status, with real capabilities from DB)
async function getModuleDetails(moduleId) {
  const fsModules = getFilesystemModules();
  const base = fsModules.find(m => m.module_id === moduleId);
  const dbModules = await getRegisteredModulesFromDb();
  const dbRecord = dbModules.get(moduleId);

  if (!base && !dbRecord) {
    throw new Error(`Module not found on filesystem or in registry: ${moduleId}`);
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

  const merged = {
    ...(base || { module_id: moduleId }),
    ...(dbRecord
      ? {
          module_name: dbRecord.module_name,
          module_type: dbRecord.module_type,
          capabilities: dbRecord.capabilities,
          command_queue: dbRecord.command_queue,
          status: dbRecord.status,
          last_heartbeat: dbRecord.last_heartbeat,
          metadata: dbRecord.metadata
        }
      : {}),
    config_id: moduleId,
    status: dbRecord ? dbRecord.status : (registryRunning ? 'online' : 'offline'),
    isHealthy: dbRecord ? dbRecord.status === 'online' : registryRunning,
    secondsSinceHeartbeat: dbRecord ? secondsSince(dbRecord.last_heartbeat) : null
  };

  return {
    success: true,
    module: merged
  };
}

module.exports = {
  getModuleHealth,
  getModuleDetails
};
