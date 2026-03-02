# Windows Installer (WiX)

This directory contains WiX Toolset scripts and resources for building a standalone Windows 64-bit installer for Pdfalyzer UI.

## Steps
1. Place WiX .wxs script(s) here to define the installer.
2. Add scripts to bundle the Java runtime, Chromium, and all required resources.
3. Document signing process (if certificate available).

## Example Structure
- `PdfalyzerInstaller.wxs` — Main WiX script
- `assets/` — Icons, images, and other installer resources
- `scripts/` — Helper scripts for build automation
- `README.md` — Build and usage instructions

## Requirements
- WiX Toolset (https://wixtoolset.org/)
- Windows 10/11 64-bit
- JRE/JDK and Chromium binaries (to be bundled)
- Optional: Code signing certificate

## Next Steps
- Add a sample PdfalyzerInstaller.wxs
- Add build instructions
- Add signing instructions
