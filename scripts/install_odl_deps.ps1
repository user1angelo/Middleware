<#
.SYNOPSIS
    Installs OpenDaylight Carbon dependencies from a local distribution into the local Maven repository.
.DESCRIPTION
    This script searches for specific ODL JARs (mdsal, controller, openflowplugin) in a provided directory
    (unzipped ODL distribution) and installs them using 'mvn install:install-file'.
.PARAMETER OdlPath
    The path to the unzipped OpenDaylight Carbon distribution (e.g., "C:\Users\Angelo\Downloads\karaf-0.6.4-Carbon").
#>
param (
    [Parameter(Mandatory=$true)]
    [string]$OdlPath
)

$Artifacts = @(
    @{ GroupId="org.opendaylight.mdsal"; ArtifactId="mdsal-binding-api"; Version="1.6.4-Carbon" },
    @{ GroupId="org.opendaylight.mdsal"; ArtifactId="mdsal-common-api"; Version="1.6.4-Carbon" },
    @{ GroupId="org.opendaylight.controller"; ArtifactId="sal-binding-api"; Version="1.6.4-Carbon" },
    @{ GroupId="org.opendaylight.openflowplugin"; ArtifactId="openflowplugin-api"; Version="0.5.4-Carbon" }
)

if (-not (Test-Path $OdlPath)) {
    Write-Error "Path not found: $OdlPath"
    exit 1
}

Write-Host "Searching for artifacts in $OdlPath..." -ForegroundColor Cyan

foreach ($art in $Artifacts) {
    $searchName = "$($art.ArtifactId)*.jar"
    $files = Get-ChildItem -Path $OdlPath -Filter $searchName -Recurse -ErrorAction SilentlyContinue
    
    $found = $false
    foreach ($file in $files) {
        # Simple heuristic: try to match version in filename if possible, or just take the first one found
        # In a proper ODL system folder, the path contains the version.
        if ($file.FullName -match $art.Version) {
            Write-Host "Found $($art.ArtifactId) ($($art.Version)): $($file.FullName)" -ForegroundColor Green
            
            Write-Host "Installing $($art.ArtifactId)..."
            & mvn install:install-file `
                "-Dfile=$($file.FullName)" `
                "-DgroupId=$($art.GroupId)" `
                "-DartifactId=$($art.ArtifactId)" `
                "-Dversion=$($art.Version)" `
                "-Dpackaging=jar" `
                "-DgeneratePom=true"
            
            if ($LASTEXITCODE -eq 0) {
                Write-Host "Successfully installed $($art.ArtifactId)." -ForegroundColor Green
                $found = $true
                break
            } else {
                Write-Host "Failed to install $($art.ArtifactId)." -ForegroundColor Red
            }
        }
    }
    
    if (-not $found) {
        Write-Warning "Could not find JAR for $($art.ArtifactId) version $($art.Version) in $OdlPath"
        Write-Host "  Attempting loose match..."
        # Try to find any jar with that artifact ID
        $looseFiles = Get-ChildItem -Path $OdlPath -Filter "$($art.ArtifactId)*.jar" -Recurse -ErrorAction SilentlyContinue
        if ($looseFiles) {
             $bestMatch = $looseFiles | Select-Object -First 1
             Write-Host "  Found candidate: $($bestMatch.FullName). Installing as $($art.Version)..." -ForegroundColor Yellow
             & mvn install:install-file `
                "-Dfile=$($bestMatch.FullName)" `
                "-DgroupId=$($art.GroupId)" `
                "-DartifactId=$($art.ArtifactId)" `
                "-Dversion=$($art.Version)" `
                "-Dpackaging=jar" `
                "-DgeneratePom=true"
             if ($LASTEXITCODE -eq 0) { $found = $true }
        }
    }
}

Write-Host "Done." -ForegroundColor Cyan
