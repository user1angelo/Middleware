$ErrorActionPreference = "Stop"

# Paths
$JdkPath = $env:JAVA_HOME
$SdkJar = "..\nis-thesis-sdk\out\nis-thesis-sdk.jar"
$JsonJar = "..\ModuleRegistryLifecycleManager\lib\json-20231013.jar" 

# Verify dependencies
if (-not $JdkPath) { Write-Error "JAVA_HOME is not set"; exit 1 }
if (-not (Test-Path (Join-Path $JdkPath "bin\javac.exe"))) { Write-Error "javac.exe not found under JAVA_HOME"; exit 1 }
if (-not (Test-Path (Join-Path $JdkPath "bin\jar.exe"))) { Write-Error "jar.exe not found under JAVA_HOME"; exit 1 }
if (-not (Test-Path $SdkJar)) { Write-Error "SDK JAR not found at $SdkJar"; exit 1 }
if (-not (Test-Path $JsonJar)) { Write-Error "JSON JAR not found at $JsonJar"; exit 1 }

$OutputDir = "out"
$JarFile = "opendaylight-module.jar"

# Cleanup
if (Test-Path $OutputDir) { Remove-Item -Recurse -Force $OutputDir }
New-Item -ItemType Directory -Path $OutputDir

# Compile
Write-Host "Compiling OpenDaylight Module..."
# Windows classpath separator is ';'
$Classpath = "$SdkJar;$JsonJar"

# Find sources (Specific to ODL to avoid Suricata dependency issues)
$sources = @(
    "src\main\java\com\nis1\thesis\udm\OpenDaylightModule.java",
    "src\main\java\com\nis1\thesis\udm\services\OpenDaylightClient.java",
    "src\main\java\com\nis1\thesis\udm\services\NetworkScannerService.java"
)

& (Join-Path $JdkPath "bin\javac.exe") -cp $Classpath -d $OutputDir $sources

# Package
Write-Host "Packaging JAR..."
& (Join-Path $JdkPath "bin\jar.exe") cf $JarFile -C $OutputDir .

Write-Host "Build Complete: $JarFile"
