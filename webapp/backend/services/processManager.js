const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs').promises;
const fsSync = require('fs');

const PROCESSES = {
  threatContextStore: {
    name: 'ThreatContextStore',
    cwd: process.env.THREAT_CONTEXT_STORE_PATH,
    command: 'java',
    args: ['-Djava.awt.headless=true', '-cp', 'out:lib/*', 'com.yourorg.middleware.ThreatContextStoreMain'],
    process: null,
    status: 'stopped',
    logFile: null
  },
  moduleRegistry: {
    name: 'ModuleRegistry',
    cwd: process.env.MODULE_REGISTRY_PATH,
    command: 'java',
    args: ['-Djava.awt.headless=true', '-cp', 'out:lib/*', 'com.yourorg.registry.ModuleRegistryMain'],
    process: null,
    status: 'stopped',
    logFile: null
  },
  workflowEngine: {
    name: 'WorkflowEngine',
    cwd: process.env.WORKFLOW_ENGINE_PATH,
    command: 'java',
    args: ['-Djava.awt.headless=true', '-cp', 'out:lib/*', 'com.yourorg.workflow.WorkflowEngineMain'],
    process: null,
    status: 'stopped',
    logFile: null
  }
};

let logService = null;

// Initialize with log service
function setLogService(service) {
  logService = service;
}

// Ensure logs directory exists
async function ensureLogsDir() {
  const logsDir = process.env.LOGS_DIR || path.join(__dirname, '../../logs');
  try {
    await fs.mkdir(logsDir, { recursive: true });
  } catch (error) {
    console.error('Failed to create logs directory:', error);
  }
  return logsDir;
}

// Start a process
async function startProcess(processKey) {
  const proc = PROCESSES[processKey];
  
  if (!proc) {
    throw new Error(`Unknown process: ${processKey}`);
  }
  
  if (proc.process) {
    return { success: false, message: `${proc.name} is already running` };
  }

  // Validate environment/path prerequisites early
  if (!proc.cwd) {
    throw new Error(`${proc.name} path is not configured. Check your backend .env file.`);
  }
  try {
    const stat = await fs.stat(proc.cwd);
    if (!stat.isDirectory()) {
      throw new Error(`${proc.name} path is not a directory: ${proc.cwd}`);
    }
  } catch (e) {
    throw new Error(`${proc.name} path does not exist: ${proc.cwd}`);
  }
  const outDir = path.join(proc.cwd, 'out');
  const libDir = path.join(proc.cwd, 'lib');
  if (!fsSync.existsSync(outDir)) {
    throw new Error(`${proc.name} is not compiled. Missing 'out' directory at ${outDir}`);
  }
  if (!fsSync.existsSync(libDir)) {
    throw new Error(`${proc.name} missing 'lib' directory at ${libDir}`);
  }
  
  const logsDir = await ensureLogsDir();
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const logFilePath = path.join(logsDir, `${processKey}_${timestamp}.log`);
  
  try {
    // Use sync write stream for logs
    const logStream = fsSync.createWriteStream(logFilePath, { flags: 'a' });
    proc.logFile = logFilePath;
    
    const childProcess = spawn(proc.command, proc.args, {
      cwd: proc.cwd,
      env: process.env,
      stdio: ['ignore', 'pipe', 'pipe']
    });
    
    proc.process = childProcess;
    proc.status = 'running';
    
    console.log(`Started ${proc.name} with PID ${childProcess.pid}`);
    
    // Handle stdout
    childProcess.stdout.setEncoding('utf8');
    childProcess.stdout.on('data', (data) => {
      const output = data.toString();
      
      // Write to log file
      logStream.write(output);
      
      // Stream to WebSocket
      console.log(`[${processKey}] stdout: ${output.substring(0, 50).replace(/\n/g, '\\n')}...`);
      if (logService) {
        logService.broadcastLog(processKey, output);
      } else {
        console.error(`[${processKey}] logService is NULL!`);
      }
    });
    
    // Handle stderr
    childProcess.stderr.setEncoding('utf8');
    childProcess.stderr.on('data', (data) => {
      const output = data.toString();
      
      // Write to log file
      logStream.write(output);
      
      // Stream to WebSocket
      console.log(`[${processKey}] stderr: ${output.substring(0, 50).replace(/\n/g, '\\n')}...`);
      if (logService) {
        logService.broadcastLog(processKey, output);
      } else {
        console.error(`[${processKey}] logService is NULL!`);
      }
    });
    
    // Handle process exit
    childProcess.on('close', (code) => {
      proc.process = null;
      proc.status = 'stopped';
      logStream.end();
      
      if (logService) {
        logService.broadcastLog(processKey, `\n[Process exited with code ${code}]\n`);
      }
      
      console.log(`${proc.name} exited with code ${code}`);
    });
    
    // Handle errors
    childProcess.on('error', (error) => {
      proc.process = null;
      proc.status = 'error';
      logStream.end();
      
      if (logService) {
        logService.broadcastLog(processKey, `\n[Process error: ${error.message}]\n`);
      }
      
      console.error(`${proc.name} error:`, error);
    });
    
    return { 
      success: true, 
      message: `${proc.name} started successfully`,
      logFile: logFilePath,
      pid: childProcess.pid
    };
    
  } catch (error) {
    proc.status = 'error';
    throw new Error(`Failed to start ${proc.name}: ${error.message}`);
  }
}

// Stop a process
async function stopProcess(processKey) {
  const proc = PROCESSES[processKey];
  
  if (!proc) {
    throw new Error(`Unknown process: ${processKey}`);
  }
  
  if (!proc.process) {
    return { success: false, message: `${proc.name} is not running` };
  }
  
  try {
    proc.process.kill('SIGTERM');
    
    // Force kill after 5 seconds if still running
    setTimeout(() => {
      if (proc.process) {
        proc.process.kill('SIGKILL');
      }
    }, 5000);
    
    return { success: true, message: `${proc.name} stopped successfully` };
  } catch (error) {
    throw new Error(`Failed to stop ${proc.name}: ${error.message}`);
  }
}

// Get status of all processes
function getAllStatus() {
  const status = {};
  
  for (const [key, proc] of Object.entries(PROCESSES)) {
    status[key] = {
      name: proc.name,
      status: proc.status,
      pid: proc.process ? proc.process.pid : null,
      logFile: proc.logFile
    };
  }
  
  return status;
}

// Stop all processes
async function stopAll() {
  const promises = [];
  
  for (const key of Object.keys(PROCESSES)) {
    if (PROCESSES[key].process) {
      promises.push(stopProcess(key));
    }
  }
  
  await Promise.allSettled(promises);
}

// Run TCSTester
async function runTest() {
  const testerPath = process.env.TCS_TESTER_PATH;
  const logsDir = await ensureLogsDir();
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const logFilePath = path.join(logsDir, `tcstester_${timestamp}.log`);
  
  return new Promise((resolve, reject) => {
    const logStream = fsSync.createWriteStream(logFilePath);
    
    const childProcess = spawn('java', [
      '-cp',
      `.:../ThreatContextStore/lib/*`,
      'TCSTester'
    ], {
      cwd: testerPath,
      env: process.env
    });
    
    let output = '';
    
    childProcess.stdout.on('data', (data) => {
      const text = data.toString();
      output += text;
      logStream.write(text);
      
      if (logService) {
        logService.broadcastLog('tcstester', text);
      }
    });
    
    childProcess.stderr.on('data', (data) => {
      const text = data.toString();
      output += text;
      logStream.write(text);
      
      if (logService) {
        logService.broadcastLog('tcstester', text);
      }
    });
    
    // Auto-respond to TCSTester prompts (option 1, then option 2 - send 10 alerts)
    setTimeout(() => {
      childProcess.stdin.write('1\n');
      setTimeout(() => {
        childProcess.stdin.write('2\n');
        setTimeout(() => {
          childProcess.stdin.end();
        }, 500);
      }, 500);
    }, 1000);
    
    childProcess.on('close', (code) => {
      logStream.close();
      resolve({
        success: code === 0,
        message: `Test completed with code ${code}`,
        output: output,
        logFile: logFilePath
      });
    });
    
    childProcess.on('error', (error) => {
      logStream.close();
      reject(new Error(`Test failed: ${error.message}`));
    });
  });
}

module.exports = {
  setLogService,
  startProcess,
  stopProcess,
  getAllStatus,
  stopAll,
  runTest
};
