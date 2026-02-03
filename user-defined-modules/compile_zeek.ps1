$ErrorActionPreference = "Stop"

# Paths
$JdkPath = $env:JAVA_HOME
$SourceFile = "src\main\java\com\nis1\thesis\udm\ZeekHttpModule.java"
$AlertFile = "src\main\java\com\nis1\thesis\udm\ZeekAlertData.java"
$SdkPath = "..\nis-thesis-sdk\out"
$GsonJar = "..\ModuleRegistryLifecycleManager\lib\gson-2.10.1.jar" 

$OutputDir = "out"
$JarFile = "zeek-http-module.jar"

# Ensure output directory exists
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

# Check for SDK
if (-not (Test-Path $SdkPath)) {
    Write-Host "SDK JAR not found at $SdkPath" -ForegroundColor Red
    Write-Host "Please compile nis-thesis-sdk first."
    exit 1
}

# Check for Gson
if (-not (Test-Path $GsonJar)) {
    Write-Host "Gson JAR not found at $GsonJar. Searching..."
    $FoundGson = Get-ChildItem -Path .. -Recurse -Filter "gson*.jar" | Select-Object -First 1
    if ($FoundGson) {
        $GsonJar = $FoundGson.FullName
        Write-Host "Found Gson at $GsonJar"
    }
    else {
        Write-Host "Gson JAR not found anywhere. Please ensure gson-*.jar is available."
        exit 1
    }
}

Write-Host "Compiling ZeekHttpModule..."

# Compile
& "$JdkPath\bin\javac.exe" -d $OutputDir -cp "$SdkPath;$GsonJar" $AlertFile $SourceFile

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
