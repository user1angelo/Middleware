const fs = require('fs');
const path = require('path');
const child_process = require('child_process');

console.log('=== Middleware Diagnostic Script ===\n');

// 1. Check CWD
const cwd = process.cwd();
console.log(`[1] Current Working Directory: ${cwd}`);
if (!cwd.endsWith('backend')) {
    console.warn('    WARNING: You should usually run this from the "webapp/backend" directory.');
}

// 2. Check Java
console.log('\n[2] Checking Java...');
try {
    const javaVersion = child_process.execSync('java -version', { stdio: 'pipe', encoding: 'utf8' });
    // Java version usually prints to stderr, try to capture that if stdout is empty
} catch (e) {
    // If execSync checks stdout/err, fine. But 'java -version' output depends on vendor.
    // The main thing is: did it throw?
    if (e.code === 'ENOENT') {
        console.error('    ERROR: "java" command not found in PATH.');
        console.error('           Please install Java (JDK 17+) or add it to your system PATH.');
    } else {
        console.log('    OK: Java found.');
    }
}

// 3. Check .env
console.log('\n[3] Checking .env file...');
const envPath = path.join(cwd, '.env');
if (!fs.existsSync(envPath)) {
    console.error(`    ERROR: .env file NOT found at: ${envPath}`);
    console.error('           Make sure you moved it from "services/" to "backend/" as instructed.');
} else {
    console.log(`    OK: Found .env at: ${envPath}`);
    require('dotenv').config({ path: envPath });
}

// 4. Check Configured Paths
console.log('\n[4] Checking Middleware Paths...');

const MIDDLEWARE_ROOT = process.env.MIDDLEWARE_ROOT || path.join(__dirname, '../../');
console.log(`    MIDDLEWARE_ROOT resolved to: ${path.resolve(MIDDLEWARE_ROOT)}`);

const pathsToCheck = [
    { key: 'THREAT_CONTEXT_STORE_PATH', default: '../../ThreatContextStore', checkCompile: true },
    { key: 'MODULE_REGISTRY_PATH', default: '../../ModuleRegistryLifecycleManager', checkCompile: true },
    { key: 'WORKFLOW_ENGINE_PATH', default: '../../WorkflowEngine', checkCompile: true },
    { key: 'TCS_TESTER_PATH', default: '../../ThreatContextStoreTester', checkCompile: false } // Tester compiles on fly
];

pathsToCheck.forEach(item => {
    const rawVal = process.env[item.key];
    const val = rawVal || item.default;
    const resolved = path.resolve(cwd, val);

    console.log(`    - ${item.key}: ${val}`);
    console.log(`      Resolved: ${resolved}`);

    if (fs.existsSync(resolved)) {
        console.log('      [OK] Directory exists.');

        if (item.checkCompile) {
            const outDir = path.join(resolved, 'out');
            if (fs.existsSync(outDir)) {
                console.log('      [OK] "out" directory exists (compiled).');
            } else {
                console.error('      [FAIL] "out" directory MISSING. This module is not compiled.');
                console.error('             Run "javac" or the build scripts for this module.');
            }
        }
    } else {
        console.error('      [FAIL] Directory does NOT exist.');
        console.error('             Check your relative path in .env or your folder structure.');
    }
    console.log('');
});

console.log('=== End of Diagnostic ===');
