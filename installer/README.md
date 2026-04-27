# PDFalyzer Studio – Build & Packaging

## Overview

The installer bundles everything needed to run PDFalyzer Studio as a standalone desktop application:

| Component | Purpose | Size |
|-----------|---------|------|
| Application JAR | Spring Boot fat JAR (`pdfalyzer-ui.jar`) | ~63 MB |
| Adoptium Temurin JRE 21 | Java runtime (x64) | ~47 MB |
| Chrome for Testing | Chromium browser in `--app` mode (no address bar) | ~183 MB |
| Launch4j | Wraps JAR as Windows EXE | build tool only |
| WiX Toolset 3.14 | Creates MSI installer | build tool only |

Final MSI: **~236 MB** (compressed).

## Quick Start (Windows)

```powershell
cd installer\windows

# 1. Download all components + build the app JAR
.\update-components.ps1

# 2. Build the MSI installer
.\build-installer.bat
```

Output: `installer\windows\output\PdfalyzerUiInstaller.msi`

## Directory Structure

```
installer/
├── README.md                          ← this file
├── maven-shade-plugin-snippet.xml     ← (legacy, not used)
├── windows/
│   ├── update-components.ps1          ← downloads JRE, Chromium, Launch4j, WiX + builds JAR
│   ├── build-installer.bat            ← builds EXE wrapper + MSI installer
│   ├── PdfalyzerInstaller.wxs         ← WiX installer definition
│   ├── standalone-assembly.xml        ← Maven assembly descriptor (legacy)
│   ├── assets/
│   │   ├── app-icon.ico               ← application icon (multi-size)
│   │   └── license.rtf                ← license dialog text for installer
│   ├── scripts/
│   │   ├── build-uber-jar.bat         ← (legacy)
│   │   └── package-exe.bat            ← (legacy)
│   ├── bundle/                        ← (gitignored) downloaded components
│   │   ├── app/pdfalyzer-ui.jar
│   │   ├── jre/                       ← Adoptium Temurin JRE 21
│   │   ├── chromium/                  ← Chrome for Testing
│   │   ├── launch4j/                  ← Launch4j build tool
│   │   ├── wix/                       ← WiX Toolset binaries
│   │   ├── pdfalyzer.bat              ← generated launcher script
│   │   └── versions.json              ← component version manifest
│   └── output/                        ← (gitignored) build artifacts
│       ├── PdfalyzerUi.exe            ← Launch4j EXE wrapper
│       └── PdfalyzerUiInstaller.msi   ← final installer
```

## Windows Installer Details

### Prerequisites

- **PowerShell 5.1+** (included with Windows 10/11)
- **Maven 3.x** and **Java 21+** on PATH (for building the app JAR)
- Internet connection (for downloading components)

No other tools need to be pre-installed. Launch4j, WiX, JRE, and Chromium are all downloaded automatically by `update-components.ps1`.

### Step 1: update-components.ps1

Downloads all external dependencies and builds the application JAR.

```powershell
# Download everything + build JAR
.\update-components.ps1

# Only download external deps (skip Maven build)
.\update-components.ps1 -SkipApp

# Only rebuild the app JAR (skip downloads)
.\update-components.ps1 -SkipDownloads

# Force re-download even if components already exist
.\update-components.ps1 -Force
```

**What it downloads:**

| Component | Source | API/URL |
|-----------|--------|---------|
| JRE 21 | [Adoptium](https://adoptium.net) | `api.adoptium.net/v3/assets/latest/21/hotspot` |
| Chromium | [Chrome for Testing](https://googlechromelabs.github.io/chrome-for-testing/) | `last-known-good-versions-with-downloads.json` |
| Launch4j 3.50 | [SourceForge](https://sourceforge.net/projects/launch4j/) | Direct download |
| WiX 3.14.1 | [GitHub](https://github.com/wixtoolset/wix3/releases) | Direct download |

Components are cached in `bundle/` and only re-downloaded if missing (or with `-Force`).

After running, check `bundle/versions.json` for installed versions.

### Step 2: build-installer.bat

Builds the MSI installer using the components in `bundle/`.

**What it does:**

1. **Launch4j** – wraps `pdfalyzer-ui.jar` as `PdfalyzerUi.exe` (uses bundled JRE at `jre/` relative path)
2. **WiX heat.exe** – harvests JRE and Chromium directories into WiX fragments (auto-generates file lists)
3. **WiX candle.exe** – compiles all `.wxs` sources
4. **WiX light.exe** – links into final `PdfalyzerUiInstaller.msi`

### What the Installer Creates

When a user runs the MSI:

- Installs to `C:\Program Files\PDFalyzer Studio\`
- Creates **Desktop shortcut** (PDFalyzer Studio)
- Creates **Start Menu folder** with launch and uninstall shortcuts
- Registers in **Add/Remove Programs** with icon

**Installed directory layout:**
```
C:\Program Files\PDFalyzer Studio\
├── pdfalyzer.bat          ← launcher (starts app + opens browser)
├── PdfalyzerUi.exe        ← alternative launcher (Launch4j wrapper)
├── app\
│   └── pdfalyzer-ui.jar   ← Spring Boot application
├── jre\                   ← bundled JRE 21
│   └── bin\java.exe
└── chromium\              ← bundled Chromium
    └── chrome.exe
```

### How the Launcher Works

`pdfalyzer.bat`:
1. Starts `javaw.exe -jar pdfalyzer-ui.jar` in the background
2. Polls `http://localhost:8080/actuator/health` until the server is ready
3. Opens Chromium in `--app` mode (no address bar, looks like a native app)
4. Uses a dedicated `chromium-profile/` directory (no interference with user's browser)

### Updating the Application

To ship an update, re-run:
```powershell
.\update-components.ps1 -SkipDownloads   # rebuild JAR only
.\build-installer.bat                     # rebuild MSI
```

The MSI uses `MajorUpgrade` so installing a new version automatically removes the old one.

### Customization

- **App icon**: replace `assets/app-icon.ico` (must be valid multi-size ICO)
- **License text**: edit `assets/license.rtf`
- **Server port**: change `--server.port=8080` in the Launch4j config (build-installer.bat) and in `pdfalyzer.bat` (generated by update-components.ps1)
- **JRE version**: change `$JRE_MAJOR` in `update-components.ps1`
- **Installer version**: update `Version="1.0.0"` in `PdfalyzerInstaller.wxs`

### Code Signing (Optional)

To sign the EXE and MSI with a code signing certificate:
```cmd
signtool sign /f cert.pfx /p password /tr http://timestamp.digicert.com /td sha256 /fd sha256 output\PdfalyzerUi.exe
signtool sign /f cert.pfx /p password /tr http://timestamp.digicert.com /td sha256 /fd sha256 output\PdfalyzerUiInstaller.msi
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `update-components.ps1` blocked by execution policy | Run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` |
| Maven build fails | Ensure `mvn` and Java 21+ are on PATH |
| Launch4j fails | Check `bundle/launch4j/launch4j.exe` exists; re-run with `-Force` |
| MSI build fails with "undefined variable" | Run `update-components.ps1` first to populate `bundle/` |
| App doesn't start after install | Check port 8080 isn't in use; check `jre/bin/java.exe` exists |
| Chromium shows security warning | Expected for unsigned builds; sign with a certificate to avoid |
