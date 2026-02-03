$ErrorActionPreference = "Stop"

# Paths
$JdkPath = $env:JAVA_HOME
$SourceFile = "src\main\java\com\nis1\thesis\udm\OpenDaylightModule.java"
$SdkPath = "..\nis-thesis-sdk\out"
# ODL module doesn't use Gson currently, but including it doesn't hurt if we add it later.
# Actually checking imports: no Gson used. So we can omit it or leave it empty.
# But just in case generic PluggableModule needs it or future changes
$GsonJar = "..\ModuleRegistryLifecycleManager\lib\gson-2.10.1.jar" 

$OutputDir = "out"
$JarFile = "opendaylight-module.jar"

# Ensure output directory exists
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

# Check for SDK
if (-not (Test-Path $SdkPath)) {
    Write-Host "SDK output not found at $SdkPath" -ForegroundColor Red
    Write-Host "Please compile nis-thesis-sdk first."
    exit 1
}

Write-Host "Compiling OpenDaylightModule..."

# Compile
# Only compiling the module file as there is no separate Data file like Zeek/Suricata
& "$JdkPath\bin\javac.exe" -d $OutputDir -cp "$SdkPath;$GsonJar" $SourceFile

if ($LASTEXITCODE -eq 0) {
    Write-Host "Compilation successful."
    
    # Create JAR
    Write-Host "Packaging $JarFile..."
    & "$JdkPath\bin\jar.exe" cf $JarFile -C $OutputDir .

    if ($LASTEXITCODE -eq 0) {
        Write-Host "JAR created: $JarFile"
        Write-Host "Location: $(Get-Item $JarFile | Select-Object -ExpandProperty FullName)"
    }
    else {
        Write-Host "JAR packaging failed."
    }
}
else {
    Write-Host "Compilation failed."
}
