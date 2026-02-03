
# Script to debug OpenDaylight Module Isolation Logic
# 1. Starts a ModuleRegistry instance in the background (if not running)
# 2. Sends a simulated MITIGATION command via RabbitMQ (using a Java helper)
# 3. Tails the logs

$MiddlewareRoot = ".."
$ModuleRegistryPath = "$MiddlewareRoot\ModuleRegistryLifecycleManager"
$UdmPath = "$MiddlewareRoot\user-defined-modules"

Write-Host "=== Debugging OpenDaylight Module Isolation ==="

# 1. Compile Helper to send RabbitMQ command
$SendAlertSrc = "$ModuleRegistryPath\SendRansomwareAlert.java"
if (-not (Test-Path $SendAlertSrc)) {
    Write-Error "SendRansomwareAlert.java not found in $ModuleRegistryPath"
    exit 1
}

Write-Host "1. Compiling SendRansomwareAlert helper..."
Push-Location $ModuleRegistryPath
javac -cp "lib/*" SendRansomwareAlert.java
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to compile helper."
    Pop-Location
    exit 1
}

# 2. Send the Alert (Simulating Workflow Engine command)
Write-Host "2. Sending INITIATE_MITIGATION command..."
# Note: We need to modify SendRansomwareAlert or create a new one if it only sends 'alerts'.
# Checking content first...
Pop-Location

Write-Host "   (If the module is running, check the Web App or Console logs now)"
Write-Host "   Running SendRansomwareAlert..."

Push-Location $ModuleRegistryPath
# Run the sender
java -cp ".;lib/*" SendRansomwareAlert
Pop-Location

Write-Host "Done. Check your module logs."
