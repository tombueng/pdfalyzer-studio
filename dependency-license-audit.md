# Dependency License Audit (March 1, 2026)

## Scope and data source
- Project: `io.pdfalyzer:pdfalyzer-ui`
- Source of truth for dependency licenses: Maven generated report at `licenses/generated/THIRD-PARTY.txt`
- Runtime/test scope inventory from:
  - `target/runtime-deps.txt`
  - `target/test-deps.txt`

## Executive verdict
- **Commercial sale:** **Yes, conditionally**. The dependency set is generally compatible with commercial distribution **if you comply with attribution/notice requirements and choose the permissive side of dual-licensed components where available**.
- **Open source release of this app:** **Yes, conditionally**. Prefer a permissive app license (e.g., Apache-2.0 or MIT) and include third-party notices.
- **Main blockers to watch:** copyleft/attribution obligations from a few runtime dependencies and bundled web/font assets.

## Key runtime licensing risks and handling

### 1) veraPDF family (`org.verapdf:*`)
Observed license metadata includes dual licensing: **GPL-3.0 OR MPL-2.0** for core/parser/validation artifacts.

Practical handling:
- For proprietary/commercial distribution, use the **MPL-2.0 path** (avoid electing GPL terms).
- Keep component notices and license texts for distributed binaries.
- If you modify veraPDF files directly, comply with MPL file-level obligations.

### 2) OpenPDF (`com.github.librepdf:openpdf:1.3.30`)
Observed as dual licensed: **LGPL-2.1 OR MPL-2.0**.

Practical handling:
- For simpler compliance in closed-source apps, many teams choose **MPL-2.0** option.
- Keep license texts and third-party notice information.

### 3) Logback (`ch.qos.logback:*`)
Observed as dual: **EPL-2.0 OR LGPL**.

Practical handling:
- Prefer **EPL-2.0** path for clearer weak-copyleft treatment.

### 4) Font Awesome WebJar (`org.webjars:font-awesome:6.4.0`)
Metadata shows **CC BY 3.0**.

Practical handling:
- Ensure required attribution in NOTICE/docs/UI about dialog.
- Verify upstream package details because WebJar metadata for icon/font packs can lag or simplify mixed upstream licenses.

## Other license families present
- Predominantly permissive: Apache-2.0, MIT, BSD-2/3, EDL-1.0, MPL-2.0.
- Some dual alternatives include GPL-with-classpath-exception or CDDL options in JAXB/activation-related artifacts.

## Is this project safe to sell commercially?
**Yes, with compliance controls in place**:
1. Include a generated third-party notice file in distributions.
2. Ship all required license texts for bundled dependencies/assets.
3. Keep attribution for CC-BY content (notably Font Awesome WebJar metadata result).
4. Track dual-license elections (MPL/EPL paths) in compliance docs.
5. Re-run dependency license audit on every release.

## If you open-source this app, which license should you pick?
Recommended options for your own code:
- **Apache-2.0** (best fit when you already depend heavily on Apache/Spring stack and want explicit patent grant).
- **MIT** (short and permissive; still must retain third-party notices/licenses).

Avoid choosing GPL for your app unless you intentionally want copyleft distribution requirements.

## Minimal release checklist
- Add top-level `LICENSE` (Apache-2.0 or MIT for your code).
- Add top-level `NOTICE` including third-party attribution summary.
- Package third-party license texts (or provide accessible URL + artifact mapping where allowed).
- Keep this report and `THIRD-PARTY.txt` as release artifacts.

## Implemented controls in this repository
- Automated generation of `licenses/generated/THIRD-PARTY.txt` during Maven build.
- Automated download of third-party license texts to `licenses/texts/`.
- Build packaging copies compliance assets into artifact `META-INF/`:
  - whole `licenses/` folder under `META-INF/license/`
  - whole `licenses/` folder under `META-INF/resources/license/` (web access)
  - `THIRD-PARTY.txt` and third-party license text files
  - manual fallback license text files for known upstream URL failures
  - project `LICENSE` and `NOTICE`
  - dual-license election policy doc
  - Font Awesome CC-BY attribution file
- Dual-license path elections documented in `licenses/compliance/dual-license-elections.md`.
- CC-BY attribution persisted in `licenses/attributions/font-awesome-cc-by-3.0.txt` and referenced from `NOTICE`.
- Release process note in `README.md` requires running `mvn clean verify` for each release.

## Important caveat
This is an engineering compliance review from dependency metadata, **not legal advice**. For commercial launch or major OSS release, have counsel review the final NOTICE/LICENSE bundle and any modified dual-licensed components.
