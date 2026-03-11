$ErrorActionPreference = "Stop"

# Paths
$SourcePath = "src\main\java"
$OutputDir = "target\classes"
$JarFile = "target\nis-thesis-sdk-1.0-SNAPSHOT.jar"

# Cleanup
if (Test-Path $OutputDir) {
    Remove-Item -Recurse -Force $OutputDir
}
if (-not (Test-Path "target")) {
    New-Item -ItemType Directory -Path "target" | Out-Null
}
New-Item -ItemType Directory -Path $OutputDir | Out-Null

# Compile
Write-Host "Compiling SDK..."
$sources = Get-ChildItem -Path $SourcePath -Filter *.java -Recurse | Select-Object -ExpandProperty FullName
javac -d $OutputDir $sources

# Package
Write-Host "Packaging JAR..."
jar cf $JarFile -C $OutputDir .

Write-Host "SDK Build Complete: $JarFile"
