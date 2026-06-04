#Requires -Version 5.1
<#
.SYNOPSIS
    Downloads / updates all bundled components for the PDFalyzer Studio Windows installer.

.DESCRIPTION
    Fetches the latest versions of:
      - Adoptium Temurin JRE 25 (x64, Windows, .zip)
      - Chromium (portable, ungoogled-chromium via GitHub releases)
      - Launch4j  (for JAR-to-EXE wrapping)
      - WiX Toolset v3 (candle / light for MSI creation)
    and builds the application uber-jar from source.

    Everything is placed under  installer\windows\bundle\  so the build script
    can assemble the final installer from known, local paths.

.PARAMETER SkipApp
    Skip building the application uber-jar (useful when only updating runtime deps).

.PARAMETER SkipDownloads
    Skip downloading external components (useful when only rebuilding the app jar).

.PARAMETER Force
    Re-download components even if they already exist locally.

.EXAMPLE
    .\update-components.ps1
    .\update-components.ps1 -SkipApp
    .\update-components.ps1 -Force
#>
[CmdletBinding()]
param(
    [switch]$SkipApp,
    [switch]$SkipDownloads,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Paths ──────────────────────────────────────────────────────────────────────
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProjectRoot = (Resolve-Path "$ScriptDir\..\..").Path
$BundleDir   = Join-Path $ScriptDir 'bundle'
$TempDir     = Join-Path $BundleDir  '_temp'

# Component target directories
$JreDir      = Join-Path $BundleDir 'jre'
$ChromiumDir = Join-Path $BundleDir 'chromium'
$Launch4jDir = Join-Path $BundleDir 'launch4j'
$WixDir      = Join-Path $BundleDir 'wix'
$AppDir      = Join-Path $BundleDir 'app'

# ── Versions / URLs ───────────────────────────────────────────────────────────
# Adoptium Temurin JRE 25 – latest GA via API (must match maven.compiler.release in pom.xml)
$JRE_MAJOR   = 25
$ADOPTIUM_API = "https://api.adoptium.net/v3/assets/latest/$JRE_MAJOR/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse"

# Launch4j
$LAUNCH4J_VERSION = '3.50'
$LAUNCH4J_URL = "https://sourceforge.net/projects/launch4j/files/launch4j-3/$LAUNCH4J_VERSION/launch4j-$LAUNCH4J_VERSION-win32.zip/download"

# WiX Toolset v3
$WIX_VERSION = '3.14.1'
$WIX_URL = "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip"

# ── Helpers ────────────────────────────────────────────────────────────────────

function Write-Step([string]$msg) {
    Write-Host "`n=====================================================================" -ForegroundColor Cyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host "=====================================================================" -ForegroundColor Cyan
}

function Ensure-Dir([string]$path) {
    if (-not (Test-Path $path)) { New-Item -ItemType Directory -Path $path -Force | Out-Null }
}

function Download-File([string]$url, [string]$outFile) {
    Write-Host "  Downloading: $url"
    Write-Host "  Target:      $outFile"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $wc = New-Object System.Net.WebClient
    $wc.Headers.Add('User-Agent', 'PDFalyzer-Installer/1.0')
    $wc.DownloadFile($url, $outFile)
    Write-Host "  Done ($([math]::Round((Get-Item $outFile).Length / 1MB, 1)) MB)" -ForegroundColor Green
}

function Extract-Zip([string]$zipPath, [string]$destDir) {
    Write-Host "  Extracting to: $destDir"
    if (Test-Path $destDir) { Remove-Item $destDir -Recurse -Force }
    Ensure-Dir $destDir
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $destDir)
    Write-Host "  Extracted." -ForegroundColor Green
}

# Flatten single top-level directory: if the zip extracts to  dest/some-folder/*,
# move contents up so they sit directly in dest/*.
function Flatten-SingleSubdir([string]$dir) {
    $children = @(Get-ChildItem -Path $dir)
    if ($children.Length -eq 1 -and $children[0].PSIsContainer) {
        $inner = $children[0].FullName
        Write-Host "  Flattening: $($children[0].Name)"
        Get-ChildItem -Path $inner | Move-Item -Destination $dir -Force
        Remove-Item $inner -Force
    }
}

function Component-Exists([string]$dir, [string]$marker) {
    return (Test-Path (Join-Path $dir $marker))
}

function Get-JavaVersion([string]$jreDir) {
    $javaExe = Join-Path $jreDir 'bin\java.exe'
    if (-not (Test-Path $javaExe)) { return 'unknown' }
    # java -version writes to stderr; capture via cmd /c to avoid PS error records
    $output = cmd /c "`"$javaExe`" -version 2>&1" 2>$null
    if ($output) { return "$($output[0])" }
    return 'installed'
}

# ── 1. Adoptium Temurin JRE ───────────────────────────────────────────────────

function Update-JRE {
    Write-Step "Adoptium Temurin JRE $JRE_MAJOR (x64 Windows)"

    if (-not $Force -and (Component-Exists $JreDir 'bin\java.exe')) {
        $ver = (Get-JavaVersion $JreDir)
        Write-Host "  Already present: $ver" -ForegroundColor Yellow
        Write-Host "  Use -Force to re-download."
        return
    }

    Write-Host "  Querying Adoptium API for latest JRE $JRE_MAJOR ..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $response = Invoke-RestMethod -Uri $ADOPTIUM_API -Headers @{ 'User-Agent' = 'PDFalyzer-Installer/1.0' }
    # The API returns an array; pick the first .zip asset
    $asset = $response | Where-Object { $_.binary.package.link -match '\.zip$' } | Select-Object -First 1
    if (-not $asset) {
        Write-Host "  ERROR: Could not find JRE download from Adoptium API." -ForegroundColor Red
        return
    }

    $downloadUrl = $asset.binary.package.link
    $releaseName = $asset.release_name
    Write-Host "  Found: $releaseName"

    Ensure-Dir $TempDir
    $zipFile = Join-Path $TempDir "temurin-jre.zip"
    Download-File $downloadUrl $zipFile
    Extract-Zip $zipFile $JreDir
    Flatten-SingleSubdir $JreDir
    Remove-Item $zipFile -Force

    $ver = (Get-JavaVersion $JreDir)
    Write-Host "  Installed: $ver" -ForegroundColor Green
}

# ── 2. Chromium ───────────────────────────────────────────────────────────────

function Update-Chromium {
    Write-Step "Chromium (official snapshot, x64 Windows)"

    if (-not $Force -and (Component-Exists $ChromiumDir 'chrome.exe')) {
        Write-Host "  Already present." -ForegroundColor Yellow
        Write-Host "  Use -Force to re-download."
        return
    }

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

    try {
        # Get latest Chromium snapshot revision
        Write-Host "  Fetching latest Chromium snapshot revision ..."
        $revUrl = 'https://www.googleapis.com/download/storage/v1/b/chromium-browser-snapshots/o/Win_x64%2FLAST_CHANGE?alt=media'
        $revision = (Invoke-WebRequest -Uri $revUrl -UseBasicParsing -Headers @{ 'User-Agent' = 'PDFalyzer-Installer/1.0' }).Content.Trim()
        Write-Host "  Latest revision: $revision"

        $chromiumUrl = "https://www.googleapis.com/download/storage/v1/b/chromium-browser-snapshots/o/Win_x64%2F$revision%2Fchrome-win.zip?alt=media"
    }
    catch {
        Write-Host "  ERROR: Chromium snapshot lookup failed: $_" -ForegroundColor Red
        Write-Host "  Please download Chromium manually into bundle\chromium\" -ForegroundColor Yellow
        return
    }

    Ensure-Dir $TempDir
    $zipFile = Join-Path $TempDir "chromium.zip"
    Download-File $chromiumUrl $zipFile
    Extract-Zip $zipFile $ChromiumDir
    Flatten-SingleSubdir $ChromiumDir
    Remove-Item $zipFile -Force

    # Verify
    if (Test-Path (Join-Path $ChromiumDir 'chrome.exe')) {
        Write-Host "  Chromium snapshot r$revision installed." -ForegroundColor Green
    } else {
        Write-Host "  WARNING: chrome.exe not found after extraction. Directory contents:" -ForegroundColor Yellow
        Get-ChildItem $ChromiumDir | Select-Object Name | Format-Table -AutoSize
    }
}

# ── 3. Launch4j ───────────────────────────────────────────────────────────────

function Update-Launch4j {
    Write-Step "Launch4j $LAUNCH4J_VERSION"

    if (-not $Force -and (Component-Exists $Launch4jDir 'launch4j.exe')) {
        Write-Host "  Already present." -ForegroundColor Yellow
        Write-Host "  Use -Force to re-download."
        return
    }

    Ensure-Dir $TempDir
    $zipFile = Join-Path $TempDir "launch4j.zip"

    Write-Host "  Downloading Launch4j $LAUNCH4J_VERSION from SourceForge ..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $LAUNCH4J_URL -OutFile $zipFile -MaximumRedirection 10 -UserAgent 'PDFalyzer-Installer/1.0'
    Write-Host "  Downloaded ($([math]::Round((Get-Item $zipFile).Length / 1MB, 1)) MB)" -ForegroundColor Green

    Extract-Zip $zipFile $Launch4jDir
    Flatten-SingleSubdir $Launch4jDir
    Remove-Item $zipFile -Force

    if (Test-Path (Join-Path $Launch4jDir 'launch4j.exe')) {
        Write-Host "  Launch4j installed." -ForegroundColor Green
    } else {
        Write-Host "  WARNING: launch4j.exe not found. Contents:" -ForegroundColor Yellow
        Get-ChildItem $Launch4jDir | Select-Object Name | Format-Table -AutoSize
    }
}

# ── 4. WiX Toolset ───────────────────────────────────────────────────────────

function Update-WiX {
    Write-Step "WiX Toolset v$WIX_VERSION"

    if (-not $Force -and (Component-Exists $WixDir 'candle.exe')) {
        Write-Host "  Already present." -ForegroundColor Yellow
        Write-Host "  Use -Force to re-download."
        return
    }

    Ensure-Dir $TempDir
    $zipFile = Join-Path $TempDir "wix.zip"
    Download-File $WIX_URL $zipFile
    Extract-Zip $zipFile $WixDir
    Remove-Item $zipFile -Force

    if (Test-Path (Join-Path $WixDir 'candle.exe')) {
        Write-Host "  WiX Toolset installed." -ForegroundColor Green
    } else {
        Write-Host "  WARNING: candle.exe not found. Contents:" -ForegroundColor Yellow
        Get-ChildItem $WixDir | Select-Object Name | Format-Table -AutoSize
    }
}

# ── 5. Application JAR ───────────────────────────────────────────────────────

function Update-AppJar {
    Write-Step "Application JAR (mvn package)"

    Ensure-Dir $AppDir

    Push-Location $ProjectRoot
    try {
        Write-Host "  Building with Maven (this may take a minute) ..."
        & mvn clean package spring-boot:repackage -DskipTests -q
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  ERROR: Maven build failed." -ForegroundColor Red
            return
        }

        # Spring Boot repackage produces an executable fat JAR
        $jar = Get-ChildItem "target\*.jar" -Exclude '*-plain.jar','*-sources.jar' |
               Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $jar) {
            Write-Host "  ERROR: No JAR found in target\." -ForegroundColor Red
            return
        }

        $dest = Join-Path $AppDir 'pdfalyzer-studio.jar'
        Copy-Item $jar.FullName $dest -Force
        Write-Host "  Built: $($jar.Name) -> bundle\app\pdfalyzer-studio.jar ($([math]::Round($jar.Length / 1MB, 1)) MB)" -ForegroundColor Green
    }
    finally {
        Pop-Location
    }
}

# ── 6. Version manifest ──────────────────────────────────────────────────────

function Write-Manifest {
    Write-Step "Writing version manifest"

    $manifest = [ordered]@{
        updated    = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
        jre        = 'not installed'
        chromium   = 'not installed'
        launch4j   = 'not installed'
        wix        = 'not installed'
        app_jar    = 'not built'
    }

    if (Test-Path "$JreDir\bin\java.exe") {
        $manifest.jre = (Get-JavaVersion $JreDir)
    }
    if (Test-Path "$ChromiumDir\chrome.exe") {
        $manifest.chromium = 'installed'
    }
    if (Test-Path "$Launch4jDir\launch4j.exe") {
        $manifest.launch4j = $LAUNCH4J_VERSION
    }
    if (Test-Path "$WixDir\candle.exe") {
        $manifest.wix = $WIX_VERSION
    }
    if (Test-Path "$AppDir\pdfalyzer-studio.jar") {
        $manifest.app_jar = "$([math]::Round((Get-Item "$AppDir\pdfalyzer-studio.jar").Length / 1MB, 1)) MB"
    }

    $manifestPath = Join-Path $BundleDir 'versions.json'
    $manifest | ConvertTo-Json -Depth 2 | Set-Content -Path $manifestPath -Encoding UTF8
    Write-Host "  Written: bundle\versions.json" -ForegroundColor Green
    $manifest.GetEnumerator() | Format-Table Name, Value -AutoSize
}

# ── Main ──────────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "  PDFalyzer Studio - Component Updater" -ForegroundColor White
Write-Host "  Project root: $ProjectRoot"
Write-Host "  Bundle dir:   $BundleDir"
Write-Host ""

Ensure-Dir $BundleDir
Ensure-Dir $TempDir

if (-not $SkipDownloads) {
    Update-JRE
    Update-Chromium
    Update-Launch4j
    Update-WiX
}

if (-not $SkipApp) {
    Update-AppJar
}

Write-Manifest

# Clean up temp dir
if (Test-Path $TempDir) {
    Remove-Item $TempDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Step "All done!"
Write-Host "  Bundle directory: $BundleDir" -ForegroundColor Green
Write-Host "  Next step: run build-installer.bat to create the MSI installer." -ForegroundColor Green
Write-Host ""
