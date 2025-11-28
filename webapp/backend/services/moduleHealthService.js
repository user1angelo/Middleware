const { Pool } = require('pg');
const fs = require('fs');
const path = require('path');

const pool = new Pool({
  host: process.env.DB_HOST,
  port: process.env.DB_PORT,
  database: process.env.DB_NAME,
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD
});

// Filesystem-based discovery: user-defined-modules is the source of truth
function getFilesystemModules() {
  const middlewareRoot = process.env.MIDDLEWARE_ROOT || path.join(__dirname, '../../../');
  const modulesRoot = process.env.MODULES_ROOT || path.join(middlewareRoot, 'user-defined-modules');
  const configDir = path.join(modulesRoot, 'config');

  if (!fs.existsSync(configDir) || !fs.statSync(configDir).isDirectory()) {
    console.warn('[moduleHealthService] No user-defined-modules/config directory at', configDir);
    return [];
  }

  const files = fs.readdirSync(configDir).filter(f => f.endsWith('.properties'));
  return files.map(filename => {
    const id = filename.replace(/\.properties$/, '');
    return {
      module_id: id,
      module_name: id,
      module_type: 'generic_udm',
      capabilities: [],
      command_queue: `${id}_commands_queue`,
      registered_at: null,
      last_heartbeat: null,
      status: 'offline',
      metadata: { source: 'filesystem', config_path: path.join(configDir, filename) },
      seconds_since_heartbeat: null
    };
  });
}

// Get health status of all registered modules
async function getModuleHealth() {
  const fsModules = getFilesystemModules();

  // If DB is unavailable, fall back purely to filesystem view
  try {
    const query = `
      SELECT 
        module_id,
        module_name,
        module_type,
        capabilities,
        command_queue,
        registered_at,
        last_heartbeat,
        status,
        metadata,
        EXTRACT(EPOCH FROM (NOW() - last_heartbeat)) as seconds_since_heartbeat
      FROM registered_modules
    `;

    const result = await pool.query(query);
    const dbById = new Map(result.rows.map(r => [r.module_id, r]));

    const merged = fsModules.map(base => {
      const configId = base.module_id; // filesystem ID based on config filename
      const db = dbById.get(base.module_id);
      if (!db) {
        return {
          ...base,
          config_id: configId,
          isHealthy: false,
          secondsSinceHeartbeat: null
        };
      }

      const seconds = db.seconds_since_heartbeat;
      const isHealthy = db.status === 'online' && seconds != null && seconds < 120;

      return {
        ...base,
        ...db,
        config_id: configId,
        isHealthy,
        secondsSinceHeartbeat: seconds != null ? Math.floor(seconds) : null
      };
    });

    return {
      success: true,
      modules: merged,
      total: merged.length,
      online: merged.filter(m => m.status === 'online').length,
      offline: merged.filter(m => m.status === 'offline').length
    };
  } catch (error) {
    console.error('[moduleHealthService] Database error, falling back to filesystem-only view:', error.message);

    // Pure filesystem view, no DB enrichment
    return {
      success: true,
      modules: fsModules.map(m => ({
        ...m,
        config_id: m.module_id,
        isHealthy: false,
        secondsSinceHeartbeat: null
      })),
      total: fsModules.length,
      online: 0,
      offline: fsModules.length
    };
  }
}

// Get a specific module's details
async function getModuleDetails(moduleId) {
  const fsModules = getFilesystemModules();
  const base = fsModules.find(m => m.module_id === moduleId);
  if (!base) {
    throw new Error(`Module not found on filesystem: ${moduleId}`);
  }

  try {
    const query = `
      SELECT * FROM registered_modules
      WHERE module_id = $1
    `;

    const result = await pool.query(query, [moduleId]);

    if (result.rowCount === 0) {
      return {
        success: true,
        module: {
          ...base,
          isHealthy: false,
          secondsSinceHeartbeat: null
        }
      };
    }

    const db = result.rows[0];
    return {
      success: true,
      module: {
        ...base,
        ...db
      }
    };
  } catch (error) {
    console.error('[moduleHealthService] getModuleDetails DB error, returning filesystem-only data:', error.message);
    return {
      success: true,
      module: base
    };
  }
}

module.exports = {
  getModuleHealth,
  getModuleDetails
};
