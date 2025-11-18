const fs = require('fs').promises;
const path = require('path');

const MIDDLEWARE_ROOT = process.env.MIDDLEWARE_ROOT;
const UDM_CONFIG_DIR = path.join(MIDDLEWARE_ROOT, 'user-defined-modules', 'config');

function toLabel(id) {
  // Simple labelizer: "prtg-module" -> "PRTG Module Config"
  const pretty = id
    .replace(/[-_]+/g, ' ')
    .split(' ')
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
  return `${pretty} Config`;
}

async function listConfigs() {
  try {
    const entries = await fs.readdir(UDM_CONFIG_DIR, { withFileTypes: true });
    const configs = entries
      .filter(e => e.isFile() && e.name.endsWith('.properties'))
      .map(e => {
        const filename = e.name;
        const id = filename.replace(/\.properties$/, '');
        return {
          id,
          name: toLabel(id),
          filename
        };
      });

    return { success: true, configs };
  } catch (error) {
    throw new Error(`Failed to list UDM configs: ${error.message}`);
  }
}

async function getConfig(id) {
  try {
    const filename = `${id}.properties`;
    const filePath = path.join(UDM_CONFIG_DIR, filename);
    const content = await fs.readFile(filePath, 'utf-8');
    return {
      success: true,
      id,
      filename,
      path: filePath,
      content
    };
  } catch (error) {
    throw new Error(`Failed to read UDM config '${id}': ${error.message}`);
  }
}

async function updateConfig(id, content) {
  try {
    const filename = `${id}.properties`;
    const filePath = path.join(UDM_CONFIG_DIR, filename);
    const backupPath = `${filePath}.backup.${Date.now()}`;

    try {
      await fs.copyFile(filePath, backupPath);
    } catch (err) {
      console.warn(`Could not create backup for ${filename}: ${err.message}`);
    }

    await fs.writeFile(filePath, content, 'utf-8');

    return {
      success: true,
      message: `UDM configuration updated for ${id}`,
      path: filePath,
      backupPath
    };
  } catch (error) {
    throw new Error(`Failed to update UDM config '${id}': ${error.message}`);
  }
}

module.exports = {
  listConfigs,
  getConfig,
  updateConfig
};
