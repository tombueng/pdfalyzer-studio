# Windows Installer

Builds a standalone MSI installer for PDFalyzer Studio that bundles JRE 21, Chromium, and the application JAR.

See the [parent README](../README.md) for full documentation.

## Quick Reference

```powershell
# Download all components + build app JAR
.\update-components.ps1

# Build the MSI installer
.\build-installer.bat
```

Output: `output\PdfalyzerUiInstaller.msi` (~236 MB)

## Files

| File | Purpose |
|------|---------|
| `update-components.ps1` | Downloads JRE, Chromium, Launch4j, WiX; builds app JAR |
| `build-installer.bat` | Wraps JAR as EXE (Launch4j) + builds MSI (WiX) |
| `PdfalyzerInstaller.wxs` | WiX installer definition (directories, shortcuts, features) |
| `assets/app-icon.ico` | Application icon (16/32/48/256px) |
| `assets/license.rtf` | License dialog shown during install |
| `bundle/` | Downloaded components (gitignored) |
| `output/` | Build artifacts (gitignored) |
