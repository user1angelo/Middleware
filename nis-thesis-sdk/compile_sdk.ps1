$ErrorActionPreference = "Stop"

# Paths
$SourcePath = "src\main\java"
$OutputDir = "out"
$JarFile = "out\nis-thesis-sdk.jar"

# Cleanup
if (Test-Path $OutputDir) {
    Remove-Item -Recurse -Force $OutputDir
}
New-Item -ItemType Directory -Path $OutputDir

# Compile
Write-Host "Compiling SDK..."
$sources = Get-ChildItem -Path $SourcePath -Filter *.java -Recurse | Select-Object -ExpandProperty FullName
javac -d $OutputDir $sources

# Package
Write-Host "Packaging JAR..."
jar cf $JarFile -C $OutputDir .

Write-Host "SDK Build Complete: $JarFile"
