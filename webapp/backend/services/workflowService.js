const fs = require('fs').promises;
const path = require('path');
const yaml = require('js-yaml');

const WORKFLOWS_PATH = path.join(process.env.WORKFLOW_ENGINE_PATH, 'workflows');

// List all workflows organized by category
async function listWorkflows() {
  try {
    const categories = await fs.readdir(WORKFLOWS_PATH);
    const workflows = {};
    
    for (const category of categories) {
      const categoryPath = path.join(WORKFLOWS_PATH, category);
      const stat = await fs.stat(categoryPath);
      
      if (stat.isDirectory()) {
        const files = await fs.readdir(categoryPath);
        const yamlFiles = files.filter(f => f.endsWith('.yml') || f.endsWith('.yaml'));
        
        workflows[category] = yamlFiles.map(f => ({
          name: f,
          path: path.join(categoryPath, f)
        }));
      }
    }
    
    return {
      success: true,
      workflows: workflows
    };
  } catch (error) {
    throw new Error(`Failed to list workflows: ${error.message}`);
  }
}

// Get a specific workflow
async function getWorkflow(category, name) {
  try {
    const workflowPath = path.join(WORKFLOWS_PATH, category, name);
    const content = await fs.readFile(workflowPath, 'utf-8');
    
    // Parse YAML to validate
    let parsed = null;
    try {
      parsed = yaml.load(content);
    } catch (yamlError) {
      console.warn('YAML parsing warning:', yamlError.message);
    }
    
    return {
      success: true,
      path: workflowPath,
      content: content,
      parsed: parsed
    };
  } catch (error) {
    throw new Error(`Failed to get workflow ${category}/${name}: ${error.message}`);
  }
}

// Create a new workflow
async function createWorkflow(category, name, content) {
  try {
    // Ensure category directory exists
    const categoryPath = path.join(WORKFLOWS_PATH, category);
    await fs.mkdir(categoryPath, { recursive: true });
    
    // Ensure name has .yml extension
    if (!name.endsWith('.yml') && !name.endsWith('.yaml')) {
      name = `${name}.yml`;
    }
    
    const workflowPath = path.join(categoryPath, name);
    
    // Check if file already exists
    try {
      await fs.access(workflowPath);
      throw new Error(`Workflow ${category}/${name} already exists`);
    } catch (err) {
      // File doesn't exist, continue
      if (err.code !== 'ENOENT') {
        throw err;
      }
    }
    
    // Validate YAML
    try {
      yaml.load(content);
    } catch (yamlError) {
      throw new Error(`Invalid YAML: ${yamlError.message}`);
    }
    
    // Write file
    await fs.writeFile(workflowPath, content, 'utf-8');
    
    return {
      success: true,
      message: `Workflow created: ${category}/${name}`,
      path: workflowPath
    };
  } catch (error) {
    throw new Error(`Failed to create workflow: ${error.message}`);
  }
}

// Update an existing workflow
async function updateWorkflow(category, name, content) {
  try {
    const workflowPath = path.join(WORKFLOWS_PATH, category, name);
    
    // Create backup
    const backupPath = `${workflowPath}.backup.${Date.now()}`;
    try {
      await fs.copyFile(workflowPath, backupPath);
    } catch (err) {
      console.warn(`Could not create backup: ${err.message}`);
    }
    
    // Validate YAML
    try {
      yaml.load(content);
    } catch (yamlError) {
      throw new Error(`Invalid YAML: ${yamlError.message}`);
    }
    
    // Write file
    await fs.writeFile(workflowPath, content, 'utf-8');
    
    return {
      success: true,
      message: `Workflow updated: ${category}/${name}`,
      path: workflowPath,
      backupPath: backupPath
    };
  } catch (error) {
    throw new Error(`Failed to update workflow: ${error.message}`);
  }
}

// Delete a workflow
async function deleteWorkflow(category, name) {
  try {
    const workflowPath = path.join(WORKFLOWS_PATH, category, name);
    
    // Create backup before deleting
    const backupPath = path.join(
      process.env.MIDDLEWARE_ROOT,
      'webapp',
      'backups',
      `${category}_${name}.backup.${Date.now()}`
    );
    
    await fs.mkdir(path.dirname(backupPath), { recursive: true });
    await fs.copyFile(workflowPath, backupPath);
    
    // Delete file
    await fs.unlink(workflowPath);
    
    return {
      success: true,
      message: `Workflow deleted: ${category}/${name}`,
      backupPath: backupPath
    };
  } catch (error) {
    throw new Error(`Failed to delete workflow: ${error.message}`);
  }
}

module.exports = {
  listWorkflows,
  getWorkflow,
  createWorkflow,
  updateWorkflow,
  deleteWorkflow
};
