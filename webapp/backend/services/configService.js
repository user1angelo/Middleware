const fs = require('fs').promises;
const path = require('path');

const CONFIG_PATHS = {
  threatContextStore: path.join(process.env.THREAT_CONTEXT_STORE_PATH, 'config.properties'),
  moduleRegistry: path.join(process.env.MODULE_REGISTRY_PATH, 'config.properties'),
  workflowEngine: path.join(process.env.WORKFLOW_ENGINE_PATH, 'config.properties')
};

// Get configuration file content
async function getConfig(programKey) {
  const configPath = CONFIG_PATHS[programKey];
  
  if (!configPath) {
    throw new Error(`Unknown program: ${programKey}`);
  }
  
  try {
    const content = await fs.readFile(configPath, 'utf-8');
    return {
      success: true,
      path: configPath,
      content: content
    };
  } catch (error) {
    throw new Error(`Failed to read config for ${programKey}: ${error.message}`);
  }
}

// Update configuration file
async function updateConfig(programKey, content) {
  const configPath = CONFIG_PATHS[programKey];
  
  if (!configPath) {
    throw new Error(`Unknown program: ${programKey}`);
  }
  
  try {
    // Create backup
    const backupPath = `${configPath}.backup.${Date.now()}`;
    try {
      await fs.copyFile(configPath, backupPath);
    } catch (err) {
      console.warn(`Could not create backup: ${err.message}`);
    }
    
    // Write new content
    await fs.writeFile(configPath, content, 'utf-8');
    
    return {
      success: true,
      message: `Configuration updated for ${programKey}`,
      path: configPath,
      backupPath: backupPath
    };
  } catch (error) {
    throw new Error(`Failed to update config for ${programKey}: ${error.message}`);
  }
}

module.exports = {
  getConfig,
  updateConfig
};
