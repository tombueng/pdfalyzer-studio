# PDFalyzer Studio – Build & Packaging

## Overview

The installer bundles everything needed to run PDFalyzer Studio as a standalone desktop application:

| Component | Purpose | Size |
|-----------|---------|------|
| Application JAR | Spring Boot fat JAR (`pdfalyzer-studio.jar`) | ~63 MB |
| Adoptium Temurin JRE 25 | Java runtime (x64) | ~47 MB |
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

Output: `installer\windows\output\PdfalyzerStudioInstaller.msi`

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
│   │   ├── banner.bmp                 ← installer top banner (493×58, branded)
│   │   ├── dialog.bmp                 ← installer welcome/exit background (493×312, branded)
│   │   └── license.rtf                ← license dialog text for installer
│   ├── scripts/
│   │   ├── build-uber-jar.bat         ← (legacy)
│   │   └── package-exe.bat            ← (legacy)
│   ├── bundle/                        ← (gitignored) downloaded components
│   │   ├── app/pdfalyzer-studio.jar
│   │   ├── jre/                       ← Adoptium Temurin JRE 25
│   │   ├── chromium/                  ← Chrome for Testing
│   │   ├── launch4j/                  ← Launch4j build tool
│   │   ├── wix/                       ← WiX Toolset binaries
│   │   └── versions.json              ← component version manifest
│   └── output/                        ← (gitignored) build artifacts
│       ├── PdfalyzerStudio.exe            ← Launch4j EXE launcher (opens the app)
│       └── PdfalyzerStudioInstaller.msi   ← final installer
```

## Windows Installer Details

### Prerequisites

- **PowerShell 5.1+** (included with Windows 10/11)
- **Maven 3.x** and **Java 25+** on PATH (for building the app JAR)
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
| JRE 25 | [Adoptium](https://adoptium.net) | `api.adoptium.net/v3/assets/latest/25/hotspot` |
| Chromium | [Chrome for Testing](https://googlechromelabs.github.io/chrome-for-testing/) | `last-known-good-versions-with-downloads.json` |
| Launch4j 3.50 | [SourceForge](https://sourceforge.net/projects/launch4j/) | Direct download |
| WiX 3.14.1 | [GitHub](https://github.com/wixtoolset/wix3/releases) | Direct download |

Components are cached in `bundle/` and only re-downloaded if missing (or with `-Force`).

After running, check `bundle/versions.json` for installed versions.

### Step 2: build-installer.bat

Builds the MSI installer using the components in `bundle/`.

**What it does:**

1. **Launch4j** – wraps `pdfalyzer-studio.jar` as `PdfalyzerStudio.exe` (uses bundled JRE at `jre/` relative path)
2. **WiX heat.exe** – harvests JRE and Chromium directories into WiX fragments (auto-generates file lists)
3. **WiX candle.exe** – compiles all `.wxs` sources
4. **WiX light.exe** – links into final `PdfalyzerStudioInstaller.msi`

### What the Installer Creates

When a user runs the MSI:

- Installs to `C:\Program Files\PDFalyzer Studio\`
- Creates **Desktop shortcut** (PDFalyzer Studio)
- Creates **Start Menu folder** with launch and uninstall shortcuts
- Registers in **Add/Remove Programs** with icon

**Installed directory layout:**
```
C:\Program Files\PDFalyzer Studio\
├── PdfalyzerStudio.exe        ← the launcher (starts app + opens browser)
├── app\
│   └── pdfalyzer-studio.jar   ← Spring Boot application
├── jre\                   ← bundled JRE 25
│   └── bin\java.exe
└── chromium\              ← bundled Chromium
    └── chrome.exe
```

### How the Launcher Works

`PdfalyzerStudio.exe` is a [Launch4j](https://launch4j.sourceforge.net/) wrapper that runs
`pdfalyzer-studio.jar` on the bundled JRE with `--pdfalyzer.desktop.launch-browser=true`. That flag
activates the `DesktopLauncher` Spring bean (see `io.pdfalyzer.config.DesktopLauncher`), which on
application-ready:

1. Opens the bundled Chromium (`chromium/chrome.exe`) in `--app` mode (no address bar, looks like a native app)
2. Points it at `http://localhost:8080` using a dedicated `%LOCALAPPDATA%\PDFalyzer Studio\chromium-profile` directory (no interference with the user's own browser)
3. Watches that Chromium window and shuts the application down when it is closed

The launch behaviour is off by default (`pdfalyzer.desktop.launch-browser=false`), so `mvn spring-boot:run`
and server/hosted deployments are unaffected — only the installer's EXE turns it on.

### Updating the Application

To ship an update, re-run:
```powershell
.\update-components.ps1 -SkipDownloads   # rebuild JAR only
.\build-installer.bat                     # rebuild MSI
```

The MSI uses `MajorUpgrade` so installing a new version automatically removes the old one.

### Customization

- **App icon**: replace `assets/app-icon.ico` (must be valid multi-size ICO)
- **Installer graphics**: replace `assets/banner.bmp` (493×58) and `assets/dialog.bmp` (493×312) — wired in via the `WixUIBannerBmp` / `WixUIDialogBmp` variables in `PdfalyzerInstaller.wxs`
- **License text**: edit `assets/license.rtf`
- **Server port**: change `--server.port=8080` in the Launch4j `cmdLine` (build-installer.bat); the `DesktopLauncher` bean reads `server.port` and points Chromium at it
- **JRE version**: change `$JRE_MAJOR` in `update-components.ps1`
- **Installer version**: update `Version="1.0.1"` in `PdfalyzerInstaller.wxs`

### Code Signing (Optional)

To sign the EXE and MSI with a code signing certificate:
```cmd
signtool sign /f cert.pfx /p password /tr http://timestamp.digicert.com /td sha256 /fd sha256 output\PdfalyzerStudio.exe
signtool sign /f cert.pfx /p password /tr http://timestamp.digicert.com /td sha256 /fd sha256 output\PdfalyzerStudioInstaller.msi
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `update-components.ps1` blocked by execution policy | Run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` |
| Maven build fails | Ensure `mvn` and Java 25+ are on PATH |
| Launch4j fails | Check `bundle/launch4j/launch4j.exe` exists; re-run with `-Force` |
| MSI build fails with "undefined variable" | Run `update-components.ps1` first to populate `bundle/` |
| App doesn't start after install | Check port 8080 isn't in use; check `jre/bin/java.exe` exists |
| Chromium shows security warning | Expected for unsigned builds; sign with a certificate to avoid |
