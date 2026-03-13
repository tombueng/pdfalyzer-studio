# Dual-License Elections

Updated: 2026-03-01

This document tracks explicit license path selections for dual-licensed runtime dependencies.

## Selected paths

1. `org.verapdf:*` (veraPDF components)
   - Published as dual-license metadata including GPL-3.0 and MPL-2.0.
   - **Election for distribution:** MPL-2.0 path.
   - Rationale: weak-copyleft, file-level obligations, compatible with commercial distribution workflows when notices are preserved.

2. `com.github.librepdf:openpdf`
   - Published as dual-license metadata including LGPL-2.1 and MPL-2.0.
   - **Election for distribution:** MPL-2.0 path.
   - Rationale: consistent with file-level copyleft policy used for this project.

3. `ch.qos.logback:*`
   - Published as dual-license metadata including EPL-2.0 and LGPL.
   - **Election for distribution:** EPL-2.0 path.
   - Rationale: aligns with project policy to prefer EPL/MPL where available.

## Compliance notes

- Keep this file in source control and update it whenever dual-licensed runtime dependencies change.
- Keep corresponding license texts and notices in distributed artifacts.
- If project legal policy changes, update elections and notify release engineering.
