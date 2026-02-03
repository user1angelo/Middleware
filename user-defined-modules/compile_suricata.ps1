$ErrorActionPreference = "Stop"

# Paths
$JdkPath = $env:JAVA_HOME
$SourceFile = "src\main\java\com\nis1\thesis\udm\SuricataHttpModule.java"
$AlertFile = "src\main\java\com\nis1\thesis\udm\SuricataAlertData.java"
$SdkPath = "..\nis-thesis-sdk\out\nis-thesis-sdk.jar"
$GsonJar = "..\user-defined-modules\lib\gson-2.10.1.jar" # Assuming gson is available here or need to locate it
$OutputDir = "out"
$JarFile = "suricata-http-module.jar"

# Ensure output directory exists
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

# Check for SDK
if (-not (Test-Path $SdkPath)) {
    Write-Host "❌ SDK JAR not found at $SdkPath" -ForegroundColor Red
    Write-Host "   Please compile nis-thesis-sdk first."
    exit 1
}

# Check for Gson (we might need to download it or point to a known location)
# For now, let's assume it's in a lib folder or we need to find it. 
# In the previous file list, I saw 'lib' in ModuleRegistryLifecycleManager.
$GsonJar = "..\ModuleRegistryLifecycleManager\lib\gson-2.10.1.jar"

if (-not (Test-Path $GsonJar)) {
    # Try looking in current folder logic or just warn
    Write-Host "⚠️  Gson JAR not found at $GsonJar. Compilation might fail if Gson is missing."
    # Let's try to find it
    $FoundGson = Get-ChildItem -Path .. -Recurse -Filter "gson*.jar" | Select-Object -First 1
    if ($FoundGson) {
        $GsonJar = $FoundGson.FullName
        Write-Host "✅ Found Gson at $GsonJar"
    }
    else {
        Write-Host "❌ Gson JAR not found anywhere. Please ensure gson-*.jar is available."
        exit 1
    }
}

Write-Host "🚀 Compiling SuricataHttpModule..."

# Compile
& "$JdkPath\bin\javac.exe" -d $OutputDir -cp "$SdkPath;$GsonJar" $AlertFile $SourceFile

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Compilation successful."
    
    # Create JAR
    Write-Host "📦 Packaging $JarFile..."
    & "$JdkPath\bin\jar.exe" cf $JarFile -C $OutputDir .

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ JAR created: $JarFile"
    }
    else {
        Write-Host "❌ JAR packaging failed."
    }
}
else {
    Write-Host "❌ Compilation failed."
}
