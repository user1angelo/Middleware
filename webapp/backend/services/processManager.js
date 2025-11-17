const { spawn, spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs').promises;
const fsSync = require('fs');

const PROCESSES = {
  threatContextStore: {
    name: 'ThreatContextStore',
    cwd: process.env.THREAT_CONTEXT_STORE_PATH,
    command: 'java',
    args: ['-cp', 'out:lib/*', 'com.yourorg.middleware.ThreatContextStoreMain'],
    process: null,
    status: 'stopped',
    logFile: null,
    requiresJavaLayout: true
  },
  moduleRegistry: {
    name: 'ModuleRegistry',
    cwd: process.env.MODULE_REGISTRY_PATH,
    command: 'java',
    args: ['-cp', 'out:lib/*', 'com.yourorg.registry.ModuleRegistryMain'],
    process: null,
    status: 'stopped',
    logFile: null,
    requiresJavaLayout: true
  },
  workflowEngine: {
    name: 'WorkflowEngine',
    cwd: process.env.WORKFLOW_ENGINE_PATH,
    command: 'java',
    args: ['-cp', 'out:lib/*', 'com.yourorg.workflow.WorkflowEngineMain'],
    process: null,
    status: 'stopped',
    logFile: null,
    requiresJavaLayout: true
  },
  opendaylight: {
    name: 'OpenDaylight',
    cwd: process.env.OPENDAYLIGHT_PATH,
    command: process.env.OPENDAYLIGHT_COMMAND,
    args: process.env.OPENDAYLIGHT_ARGS ? process.env.OPENDAYLIGHT_ARGS.split(' ') : [],
    process: null,
    status: 'stopped',
    logFile: null,
    requiresJavaLayout: false,
    headful: true
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

// Helper: find an installed terminal emulator
function findTerminal() {
  const candidates = [
    {
      cmd: 'gnome-terminal',
      buildArgs: (title, cwd, shellCmd) => ['--title', title, '--', 'bash', '-lc', shellCmd]
    },
    {
      cmd: 'konsole',
      buildArgs: (title, cwd, shellCmd) => ['--workdir', cwd, '--hold', '--title', title, '-e', 'bash', '-lc', shellCmd]
    },
    {
      cmd: 'xfce4-terminal',
      buildArgs: (title, cwd, shellCmd) => ['--hold', '--title', title, '--working-directory', cwd, '-e', `bash -lc "${shellCmd.replace(/"/g, '\\\"')}"`]
    },
    {
      cmd: 'alacritty',
      buildArgs: (title, cwd, shellCmd) => ['-t', title, '-e', 'bash', '-lc', shellCmd]
    },
    {
      cmd: 'kitty',
      buildArgs: (title, cwd, shellCmd) => ['@', 'launch', '--title', title, 'bash', '-lc', shellCmd]
    },
    {
      cmd: 'xterm',
      buildArgs: (title, cwd, shellCmd) => ['-T', title, '-hold', '-e', 'bash', '-lc', shellCmd]
    }
  ];

  for (const c of candidates) {
    try {
      const res = spawnSync('which', [c.cmd], { stdio: 'ignore' });
      if (res.status === 0) return c;
    } catch (_) { /* ignore */ }
  }
  return null;
}

function shellEscapeArg(arg) {
  if (arg === undefined || arg === null) return '';
  return `'${String(arg).replace(/'/g, "'\\''")}'`;
}

function buildTailCommand(cwd, logPath) {
  const cd = `cd ${shellEscapeArg(cwd)}`;
  const tail = `tail -n +1 -f ${shellEscapeArg(logPath)}`;
  // Keep the window open after exit
  const pause = 'echo; read -n1 -s -r -p "Press any key to close"';
  return `${cd} && ${tail}; ${pause}`;
}

function buildHeadfulCommand(cwd, command, args = []) {
  const cd = `cd ${shellEscapeArg(cwd)}`;
  const cmdParts = [shellEscapeArg(command), ...args.map(shellEscapeArg)];
  const cmd = cmdParts.join(' ');
  return `${cd} && ${cmd}`;
}

function openTerminalForLog(title, cwd, logPath) {
  // Allow disabling via env if needed
  if ((process.env.DISABLE_TERMINAL_OPEN || 'false').toLowerCase() === 'true') return;

  const term = findTerminal();
  if (!term) {
    console.warn('No supported terminal emulator found. Skipping opening terminal window.');
    return;
  }

  const cmd = buildTailCommand(cwd, logPath);
  const args = term.buildArgs(title, cwd, cmd);

  try {
    const child = spawn(term.cmd, args, {
      cwd,
      env: process.env,
      detached: true,
      stdio: 'ignore'
    });
    child.unref();
    console.log(`Opened terminal '${term.cmd}' to tail logs for ${title}`);
  } catch (e) {
    console.warn(`Failed to open terminal '${term.cmd}':`, e.message);
  }
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

  if (!proc.command) {
    throw new Error(`${proc.name} command is not configured. Check your backend .env file.`);
  }

  // For Java-based middleware components, enforce expected build layout
  if (proc.requiresJavaLayout !== false) {
    const outDir = path.join(proc.cwd, 'out');
    const libDir = path.join(proc.cwd, 'lib');
    if (!fsSync.existsSync(outDir)) {
      throw new Error(`${proc.name} is not compiled. Missing 'out' directory at ${outDir}`);
    }
    if (!fsSync.existsSync(libDir)) {
      throw new Error(`${proc.name} missing 'lib' directory at ${libDir}`);
    }
  }

  // Headful mode: run inside a real terminal so user can interact
  if (proc.headful) {
    const term = findTerminal();
    if (!term) {
      throw new Error('No supported terminal emulator found. Cannot start headful process.');
    }

    const shellCmd = buildHeadfulCommand(proc.cwd, proc.command, proc.args || []);
    const args = term.buildArgs(proc.name, proc.cwd, shellCmd);

    try {
      const childProcess = spawn(term.cmd, args, {
        cwd: proc.cwd,
        env: process.env,
        detached: false,
        stdio: 'ignore'
      });

      proc.process = childProcess;
      proc.status = 'running';
      proc.logFile = null;

      console.log(`Started ${proc.name} in interactive terminal '${term.cmd}' with PID ${childProcess.pid}`);

      childProcess.on('close', (code) => {
        proc.process = null;
        proc.status = 'stopped';

        if (logService) {
          logService.broadcastLog(processKey, `\n[${proc.name} terminal exited with code ${code}]\n`);
        }

        console.log(`${proc.name} terminal exited with code ${code}`);
      });

      childProcess.on('error', (error) => {
        proc.process = null;
        proc.status = 'error';

        if (logService) {
          logService.broadcastLog(processKey, `\n[Process error: ${error.message}]\n`);
        }

        console.error(`${proc.name} error:`, error);
      });

      return {
        success: true,
        message: `${proc.name} started in interactive terminal`,
        logFile: null,
        pid: childProcess.pid
      };
    } catch (error) {
      proc.status = 'error';
      throw new Error(`Failed to start ${proc.name}: ${error.message}`);
    }
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

    // Open a terminal window to live-tail the log
    openTerminalForLog(proc.name, proc.cwd, logFilePath);
    
    // Handle stdout
    childProcess.stdout.setEncoding('utf8');
    childProcess.stdout.on('data', (data) => {
      const output = data.toString();
      
      // Write to log file
      logStream.write(output);
      
      // Stream to WebSocket
      console.log(`[${processKey}] stdout (${output.length} bytes):`, output.substring(0, 100).replace(/\n/g, ' '));
      if (logService) {
        console.log(`[${processKey}] Broadcasting to WebSocket...`);
        logService.broadcastLog(processKey, output);
      } else {
        console.error(`[${processKey}] ERROR: logService is NULL - cannot broadcast logs!`);
      }
    });
    
    // Handle stderr
    childProcess.stderr.setEncoding('utf8');
    childProcess.stderr.on('data', (data) => {
      const output = data.toString();
      
      // Write to log file
      logStream.write(output);
      
      // Stream to WebSocket
      console.log(`[${processKey}] stderr (${output.length} bytes):`, output.substring(0, 100).replace(/\n/g, ' '));
      if (logService) {
        console.log(`[${processKey}] Broadcasting stderr to WebSocket...`);
        logService.broadcastLog(processKey, output);
      } else {
        console.error(`[${processKey}] ERROR: logService is NULL - cannot broadcast logs!`);
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
