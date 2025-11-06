const { Pool } = require('pg');

const pool = new Pool({
  host: process.env.DB_HOST,
  port: process.env.DB_PORT,
  database: process.env.DB_NAME,
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD
});

// Get health status of all registered modules
async function getModuleHealth() {
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
      ORDER BY status DESC, last_heartbeat DESC
    `;
    
    const result = await pool.query(query);
    
    return {
      success: true,
      modules: result.rows.map(module => ({
        ...module,
        isHealthy: module.status === 'online' && module.seconds_since_heartbeat < 120,
        secondsSinceHeartbeat: Math.floor(module.seconds_since_heartbeat)
      })),
      total: result.rowCount,
      online: result.rows.filter(m => m.status === 'online').length,
      offline: result.rows.filter(m => m.status === 'offline').length
    };
  } catch (error) {
    console.error('Database error:', error);
    throw new Error(`Failed to get module health: ${error.message}`);
  }
}

// Get a specific module's details
async function getModuleDetails(moduleId) {
  try {
    const query = `
      SELECT * FROM registered_modules
      WHERE module_id = $1
    `;
    
    const result = await pool.query(query, [moduleId]);
    
    if (result.rowCount === 0) {
      throw new Error(`Module not found: ${moduleId}`);
    }
    
    return {
      success: true,
      module: result.rows[0]
    };
  } catch (error) {
    throw new Error(`Failed to get module details: ${error.message}`);
  }
}

module.exports = {
  getModuleHealth,
  getModuleDetails
};
