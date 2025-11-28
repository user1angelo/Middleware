const fs = require('fs').promises;
const path = require('path');

const ROOT = process.env.MIDDLEWARE_ROOT || path.join(__dirname, '../../../');

const CONFIG_PATHS = {
  threatContextStore: path.join(
    process.env.THREAT_CONTEXT_STORE_PATH || path.join(ROOT, 'ThreatContextStore'),
    'config.properties'
  ),
  moduleRegistry: path.join(
    process.env.MODULE_REGISTRY_PATH || path.join(ROOT, 'ModuleRegistryLifecycleManager'),
    'config.properties'
  ),
  workflowEngine: path.join(
    process.env.WORKFLOW_ENGINE_PATH || path.join(ROOT, 'WorkflowEngine'),
    'config.properties'
  ),
  // TCSTester (ThreatContextStoreTester Java client)
  tcsTester: path.join(
    process.env.TCS_TESTER_PATH || path.join(ROOT, 'ThreatContextStoreTester'),
    'tcs_tester.properties'
  ),
  // OpenDaylight is config-only: we expose a simple properties file under user-defined-modules/config
  opendaylight: path.join(
    process.env.OPENDAYLIGHT_CONFIG_PATH || path.join(ROOT, 'user-defined-modules', 'config', 'opendaylight.properties')
  )
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
    // If the config file does not exist yet, create a sensible default
    if (error.code === 'ENOENT') {
      let defaultContent = '';

      if (programKey === 'tcsTester') {
        defaultContent = [
          '# TCSTester (ThreatContextStore Tester) configuration',
          '# RabbitMQ connection settings used by TCSTester',
          'rabbitmq.host=192.168.1.8',
          'rabbitmq.port=5672',
          'rabbitmq.user=user',
          'rabbitmq.password=password',
          'rabbitmq.alerts_queue=alerts_queue',
          'rabbitmq.workflow_queue=workflow_queue',
          ''
        ].join('\n');
      }

      try {
        await fs.mkdir(path.dirname(configPath), { recursive: true });
        await fs.writeFile(configPath, defaultContent, 'utf-8');
      } catch (writeErr) {
        throw new Error(`Failed to initialize config for ${programKey}: ${writeErr.message}`);
      }

      return {
        success: true,
        path: configPath,
        content: defaultContent
      };
    }

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
