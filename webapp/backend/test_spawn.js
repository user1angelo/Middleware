const { spawn, spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs');

console.log('=== Process Spawn Test ===');
const cwd = process.cwd();
console.log('Current CWD:', cwd);

// 1. Load .env
const envPath = path.join(cwd, '.env');
if (fs.existsSync(envPath)) {
    console.log('Found .env at:', envPath);
    require('dotenv').config({ path: envPath });
} else {
    console.warn('WARNING: .env not found at', envPath);
}

// 2. Resolve Paths
const MIDDLEWARE_ROOT = process.env.MIDDLEWARE_ROOT || path.join(__dirname, '../../');
const MODULE_REGISTRY_PATH = process.env.MODULE_REGISTRY_PATH || '../../ModuleRegistryLifecycleManager';
const targetCwd = path.resolve(cwd, MODULE_REGISTRY_PATH);

console.log('Target Module CWD:', targetCwd);

// 3. Verify Directory
if (!fs.existsSync(targetCwd)) {
    console.error('FATAL: Target directory does not exist!');
    process.exit(1);
}

// 4. Test spawnSync('java -version')
console.log('\n[Test 1] Synchronous check for java...');
try {
    const res = spawnSync('java', ['-version'], { encoding: 'utf8' });
    if (res.error) {
        console.error('FAIL: spawnSync error:', res.error);
    } else {
        console.log('SUCCESS: java found.');
        console.log('Version info:', (res.stderr || res.stdout).split('\n')[0]);
    }
} catch (e) {
    console.error('FAIL: Exception checking java:', e);
}

// 5. Test real spawn capabilities
console.log('\n[Test 2] Spawning process in target CWD...');
const child = spawn('java', ['-version'], {
    cwd: targetCwd,
    env: process.env
});

child.on('error', (err) => {
    console.error('FAIL: Spawn error event:', err);
    console.error('      Code:', err.code); // Look for ENOENT
    console.error('      Path:', err.path);
    console.error('      Syscall:', err.syscall);
});

child.stdout.on('data', d => console.log('STDOUT:', d.toString().trim()));
child.stderr.on('data', d => console.log('STDERR:', d.toString().trim()));

child.on('close', (code) => {
    console.log(`Child exited with code ${code}`);
    if (code === 0) console.log('SUCCESS: Full spawn test passed.');
    else console.log('WARN: Child exited with non-zero code (this might be fine if just version info)');
});
