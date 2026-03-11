$ErrorActionPreference = "Stop"

# Paths
$JdkPath = $env:JAVA_HOME
$sdkJarCandidates = @(
    "..\nis-thesis-sdk\target\nis-thesis-sdk-1.0-SNAPSHOT.jar"
)
$SdkJar = $null
foreach ($candidate in $sdkJarCandidates) {
    if (Test-Path $candidate) {
        $SdkJar = $candidate
        break
    }
}
$JsonJar = "..\ModuleRegistryLifecycleManager\lib\json-20231013.jar"
$GsonJar = "..\ModuleRegistryLifecycleManager\lib\gson-2.13.1.jar"

# Verify dependencies
if (-not (Test-Path $SdkJar)) { Write-Error "SDK JAR not found at $SdkJar"; exit 1 }
if (-not (Test-Path $JsonJar)) { Write-Error "JSON JAR not found at $JsonJar"; exit 1 }
if (-not (Test-Path $GsonJar)) { Write-Error "GSON JAR not found at $GsonJar"; exit 1 }

$TargetDir = "target"
$OutputDir = "$TargetDir\classes"
$JarFile = "$TargetDir\suricata-http-module.jar"

# Cleanup
if (Test-Path $OutputDir) { Remove-Item -Recurse -Force $OutputDir }
if (-not (Test-Path $TargetDir)) { New-Item -ItemType Directory -Path $TargetDir | Out-Null }
New-Item -ItemType Directory -Path $OutputDir | Out-Null

# Compile
Write-Host "Compiling Suricata HTTP Module..."
# Windows classpath separator is ';'
$Classpath = "$SdkJar;$JsonJar;$GsonJar"

# Find sources (Specific to Suricata module)
$sources = @(
    "src\main\java\com\nis1\thesis\udm\SuricataHttpModule.java",
    "src\main\java\com\nis1\thesis\udm\SuricataAlertData.java"
)

javac -cp $Classpath -d $OutputDir $sources

# Package
Write-Host "Packaging JAR..."
jar cf $JarFile -C $OutputDir .

Write-Host "Build Complete: $JarFile"
Write-Host "Class files directory: $OutputDir"
