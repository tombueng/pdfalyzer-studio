<p align="center">
  <img src="logo.jpg" alt="PDFalyzer Studio" width="280">
</p>

<p align="center">

[![CI](https://github.com/tombueng/pdfalyzer-ui/actions/workflows/ci.yml/badge.svg)](https://github.com/tombueng/pdfalyzer-ui/actions/workflows/ci.yml)
[![CodeQL](https://github.com/tombueng/pdfalyzer-ui/actions/workflows/codeql.yml/badge.svg)](https://github.com/tombueng/pdfalyzer-ui/actions/workflows/codeql.yml)
[![Java 21](https://img.shields.io/badge/Java-21-blue?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PDFBox](https://img.shields.io/badge/PDFBox-3.0-red?logo=apache&logoColor=white)](https://pdfbox.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?logo=apache&logoColor=white)](LICENSE)

</p>

PDFalyzer Studio is a Spring Boot web application for inspecting, validating, and editing PDF internals through an interactive browser UI.

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.8+

### Run locally

```bash
mvn spring-boot:run
```

Open `http://localhost:8080`.

### Build and test

```bash
mvn clean verify
```

## Licensing and Release Compliance

- Project license: [LICENSE](LICENSE) (Apache-2.0).
- Project notice file: [NOTICE](NOTICE).
- Dependency license audit source: `licenses/generated/THIRD-PARTY.txt`.
- Generated dependency license texts: `licenses/texts/`.
- Manual fallback texts for intermittent download failures: `licenses/manual/`.
- Dual-license elections: `licenses/compliance/dual-license-elections.md`.
- CC-BY attribution: `licenses/attributions/font-awesome-cc-by-3.0.txt`.

### What is included in packaged artifacts

During build, Maven copies the entire root `licenses/` folder into the application artifact under:

- `META-INF/license/**`
- `META-INF/resources/license/**` (web-accessible)
- `META-INF/LICENSE`
- `META-INF/NOTICE`

Web overview page:
- `/license` (lists all bundled documents with links)

### Release command (must run for every release)

```bash
mvn clean verify
```

This regenerates third-party notices and license texts from the current dependency graph, ensuring the release artifact is updated with current compliance data.

## Tech Stack (Current)

| Area | Technology |
|------|------------|
| Backend | Spring Boot 3.5.11, Spring MVC |
| Java | Java 21 |
| PDF parsing/editing | Apache PDFBox 3.0.0, OpenPDF 1.3.30 |
| PDF/A validation | veraPDF libraries 1.26.1 |
| UI shell | Thymeleaf |
| Frontend | Vanilla JavaScript modules + jQuery |
| Rendering | pdf.js 3.11.174 (CDN) |
| Styling | Bootswatch Cyborg (Bootstrap 5.3.x), Font Awesome 6.4.0 |
| Color theming | CSS custom properties + `color-mix()`, 100 built-in themes, localStorage persistence |

## Architecture

- Single-page style UI served from `/` (Thymeleaf template).
- REST API under `/api/*`.
- PDF data is stored in in-memory sessions (UUID-based session id).
- No database.

## Current Features

### Upload and viewer

- Upload from file picker or drag-and-drop.
- 100 MB upload limit (client + server constraints).
- Renders all pages using pdf.js in a scrollable canvas viewer.
- If `src/main/resources/static/test.pdf` exists, it auto-loads on startup.

### Password-protected PDFs

- Password-protected PDFs are detected on upload and a dialog is shown.
- The password is tested as a user password (standard open) using PDFBox.
- On success the document is decrypted in memory for rendering; the original encryption metadata (algorithm, key length, permissions) is preserved in the session.
- The original protection is automatically re-applied on export by default (see Export section below).

### Structural tree and inspection

- Rich hierarchical tree with icons, per-node metadata panel, and lazy expansion.
- Search (`/api/tree/{sessionId}/search`) across node names and properties.
- Tree node selection can scroll/highlight matching PDF areas when geometry is available.
- PDF click can map back to tree node/field selection.

### Tabs (7)

| Tab | Key | Behavior |
|-----|-----|----------|
| Structure | `1` | Full semantic tree |
| Forms | `2` | Form/field subtree |
| Fonts | `3` | Font analyzer table + actions |
| Validation | `4` | Internal validator + veraPDF run/export |
| Raw COS | `5` | Raw COS tree |
| Bookmarks | `6` | Bookmark subtree |
| Attachments | `7` | Embedded file list/actions |

### Font inspector

- Lists fonts with type, embedded/subset flags, and issues.
- Supports charmap view, usage-area visualization, and embedded font extraction.

### Validation

- Built-in rule-based validator with exportable text report.
- Smart issue explainer: each validation finding includes a collapsible explanation of the root cause and suggested fix, along with a confidence badge and a direct link to the relevant tab.
- Optional veraPDF execution via API with in-UI rendering (HTML/XML/plain output handling).
- veraPDF report can be exported to HTML/PDF from the Validation tab.

### Editing capabilities

- Form field creation workflow (Text, Checkbox, Combo, Radio, Signature).
- Draw rectangle on page, configure field id/options, queue changes, then save in batch.
- **Field designer** (when edit mode is active):
  - **Snap-to-grid** — toggle with `G`; snaps drawn/dragged fields to a 10-pt grid.
  - **Copy / paste** (`Ctrl+C` / `Ctrl+V`) — copies selected fields and pastes them offset by one grid unit.
  - **Align / distribute** — align left/right/top/bottom, center horizontally/vertically, distribute evenly (requires 2+ fields for align, 3+ for distribute).
  - **Match size** — match width, height, or both to the first selected field (requires 2+ fields).
  - **Multi-select** — select multiple fields and apply bulk operations.
- Field options updates for selected fields.
- Field rectangle updates and field deletion API support.
- COS editing from tree:
  - edit primitive values,
  - add dictionary/array entries,
  - remove entries,
  - remove image/resource dictionary entries.

### Export and protection

When downloading a PDF that was originally encrypted, an **Export Protection** dialog is shown offering three choices:

| Choice | Behaviour |
|--------|-----------|
| Keep original protection *(default)* | Re-encrypts using the same algorithm and the password that was used to unlock the session. Original access permissions (print, modify, extract) are preserved. |
| Use different settings | User specifies a separate user password (required to open), owner password (required to change permissions), and encryption algorithm (AES-256, AES-128, RC4-128, or RC4-40). |
| Export without protection | Returns the decrypted bytes as-is. |

For non-encrypted PDFs the dialog is skipped and the file downloads directly.

### Resource and attachment handling

- Preview/download stream resources (`/api/resource/...`).
- Preview/download embedded attachments (`/api/attachment/...`).

### Keyboard shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+O` | Open PDF picker |
| `Ctrl+F` | Focus search |
| `Ctrl+S` | Download PDF (shows protection dialog if encrypted) |
| `Ctrl+E` | Preselect Text field placement mode |
| `Esc` | Clear focused search input |
| `1`..`7` | Switch tabs |
| `G` | Toggle snap-to-grid *(edit mode only)* |
| `Ctrl+C` | Copy selected fields *(edit mode only)* |
| `Ctrl+V` | Paste fields *(edit mode only)* |

Keyboard shortcuts are suppressed while any modal dialog is open.

## Main API Endpoints

### Session and tree

- `POST /api/upload`
- `POST /api/session/{sessionId}/unlock`
- `GET /api/tree/{sessionId}`
- `GET /api/tree/{sessionId}/search?q=...`
- `GET /api/tree/{sessionId}/export`
- `GET /api/tree/{sessionId}/raw-cos`

### PDF bytes/download

- `GET /api/pdf/{sessionId}`
- `GET /api/pdf/{sessionId}/download` — direct download (no re-encryption)
- `POST /api/pdf/{sessionId}/download` — download with optional re-encryption (`DownloadProtectionRequest` body)

### Fonts

- `GET /api/fonts/{sessionId}`
- `GET /api/fonts/{sessionId}/page/{pageNum}`
- `GET /api/fonts/{sessionId}/charmap/{pageNum}/{fontObjectId}`
- `GET /api/fonts/{sessionId}/usage/{objNum}/{genNum}`
- `GET /api/fonts/{sessionId}/extract/{objNum}/{genNum}`

### Validation

- `GET /api/validate/{sessionId}`
- `GET /api/validate/{sessionId}/verapdf`
- `GET /api/validate/{sessionId}/export`

### Editing

- `POST /api/cos/{sessionId}/update`
- `POST /api/edit/{sessionId}/add-field`
- `POST /api/edit/{sessionId}/add-fields`
- `DELETE /api/edit/{sessionId}/field/{fieldName}`
- `POST /api/edit/{sessionId}/field/{fieldName}/value`
- `POST /api/edit/{sessionId}/field/{fieldName}/choices`
- `POST /api/edit/{sessionId}/field/{fieldName}/rect`
- `POST /api/edit/{sessionId}/fields/options`

### Resources and diagnostics

- `GET /api/resource/{sessionId}/{objNum}/{genNum}`
- `GET /api/attachment/{sessionId}/{fileName}`
- `POST /api/client-errors`

## Color Themes

PDFalyzer Studio ships with **100 built-in color themes** organized into 5 groups.
Click the palette (<i class="fas fa-palette"></i>) button in the **status bar** (bottom-right) to open the accordion picker — themes apply instantly and your choice is saved to `localStorage`.

### How the color system works

Every theme defines **10 base CSS custom properties** on `:root` (or a `[data-theme="…"]` override):

| Variable | Role |
|----------|------|
| `--c-bg` | Darkest background surface |
| `--c-surface` | Raised panel / sidebar |
| `--c-card` | Elevated card / header |
| `--c-accent` | Primary interactive / brand color |
| `--c-err` | Error / destructive |
| `--c-warn` | Warning |
| `--c-ok` | Success / positive |
| `--c-text` | Body text |
| `--c-text-btn` | Text on accent-coloured buttons |
| `--c-input-bg` | Input / form-field background |
| `--c-title` | Heading / title color |

All other semantic aliases (`--accent`, `--accent-dim`, `--border`, `--text-muted`, `--highlight`, `--input-bg`, `--title`, `--btn-text`, …) are derived from these base variables using CSS `color-mix()`.
Light / pastel themes additionally override `--text-muted` explicitly so popup and modal text always has sufficient contrast.

### Theme groups (20 themes each)

| Group | Original 10 | New 10 |
|-------|-------------|--------|
| **Cyberpunk / Neon** | Void Blue *(default)*, Neon Tokyo, Acid Rain, Synthwave, Cyberpunk Gold, Matrix, Plasma, Outrun, Ultraviolet, Electra | Hot Wire, Laser Lime, Quantum Blue, Ghost Signal, Neon Coral, Techno Cyan, Danger Zone, Hologram, Static Discharge, Plasma Storm |
| **Dark Elegant** | Midnight Rose, Obsidian, Onyx, Stealth, Abyss, Carbon, Eclipse, Nightshade, Dark Matter, Phantom | Bordeaux, Slate Blue, Tungsten, Charcoal, Velvet Void, Iron Forge, Dusk Embers, Deep Burgundy, Graphite, Onyx Gold |
| **Nature / Organic** | Forest, Ocean Deep, Volcanic, Autumn, Aurora, Tundra, Jungle, Lagoon, Amber Cave, Storm | Desert Bloom, Coral Reef, Monsoon, Redwood, Wildfire, Ice Field, Savanna, Canyon, Peatland, Estuary |
| **Pastel / Light** | Rose Quartz, Cotton Candy, Mint Dream, Cloud Day, Lavender Fields, Peach Cream, Arctic, Parchment, Sakura, Fog | Butter, Powder Pink, Seafoam, Lilac Dream, Apricot, Morning Mist, Vanilla, Pistachio, Cornflower, Almond |
| **Retro / Terminal** | Amber Terminal, Phosphor Green, CGA Blue, VHS Glitch, Solarized Dark, Monokai, Dracula, Nord, Gruvbox, Commodore 64 | Apple II, Game Boy, BBS Terminal, Dev Console, GitHub Dark, Sublime, Tokyo Night, One Dark, Catppuccin, Everforest |

### Adding a custom theme

Add a new entry to [colors.css](src/main/resources/static/colors.css) and [app-theme.js](src/main/resources/static/js/app-theme.js):

```css
/* colors.css */
[data-theme="my-theme"] {
    --c-bg: #0d0d0d; --c-surface: #1a1a1a; --c-card: #2a2a2a;
    --c-accent: #ff6600; --c-err: #ff2222; --c-warn: #ffaa00; --c-ok: #44cc88;
    --c-text: #f0f0f0;
    /* optional overrides for dark buttons / light themes: */
    /* --c-text-btn: #ffffff; --text-muted: #aaaaaa; */
}
```

```js
// app-theme.js  →  add to the appropriate group's themes array:
// [id, label, bg-swatch, card-swatch, accent-swatch, ok-swatch]
['my-theme', 'My Theme', '#0d0d0d', '#2a2a2a', '#ff6600', '#44cc88'],
```

## Known Limitations

- Session storage is in-memory only (no persistence across restart).
- Not all PDF features are fully editable; focus is forms + COS-level manipulations.
- Some complex content-stream level mappings may be partial depending on PDF structure.
- When a PDF is unlocked with the owner password only (without a user password), the user password cannot be recovered. Re-export in "keep" mode will use the owner password for both roles.
- Memory-bound sessions — large PDFs are held entirely in memory; no disk-based fallback.
- CDN dependency — pdf.js is loaded from cdnjs.cloudflare.com; offline use requires bundling.

## Project Layout

- Backend: `src/main/java/io/pdfalyzer/...`
- Frontend static assets: `src/main/resources/static/...`
- Thymeleaf templates: `src/main/resources/templates/` (index.html + fragments/)

## Notes

- `requirements.md` captures early/aspirational requirements and is not always identical to current implementation.
- **Client JS error capture** -- Browser runtime errors are captured and sent to backend logs via `/api/client-errors`

## Roadmap

See [ideas.md](ideas.md) for the full backlog.

Effort legend:
- **S** = small (about 1-3 days)
- **M** = medium (about 1-2 weeks)
- **L** = large (multi-week / multi-sprint)

Suggested next items:
- **S** Undo/redo for pending edits
- **S** Diff summary after save
- **M** Dual-PDF compare mode
- **M** Content-stream inspector
- **L** Rule packs + CLI companion

---

## REST API Reference

All API endpoints are under `/api/`. Responses are JSON unless otherwise noted.

| Method | Endpoint | Description | Request | Response |
|--------|----------|-------------|---------|----------|
| POST | `/api/upload` | Upload and parse a PDF | `multipart/form-data` with `file` field | `{sessionId, filename, pageCount, tree, encryptionInfo}` |
| POST | `/api/session/{sessionId}/unlock` | Unlock a password-protected session | `{password}` (JSON) | `{sessionId, filename, pageCount, tree, encryptionInfo}` |
| GET | `/api/tree/{sessionId}` | Get full structure tree | -- | `PdfNode` (JSON) |
| GET | `/api/tree/{sessionId}/search?q=` | Search tree nodes | Query param `q` | `PdfNode[]` |
| GET | `/api/tree/{sessionId}/export` | Download tree as JSON | -- | `PdfNode` (attachment) |
| GET | `/api/pdf/{sessionId}` | Get raw PDF bytes | -- | `application/pdf` |
| GET | `/api/pdf/{sessionId}/download` | Download PDF as file (no re-encryption) | -- | `application/pdf` (attachment) |
| POST | `/api/pdf/{sessionId}/download` | Download PDF with optional re-encryption | `DownloadProtectionRequest` (JSON) | `application/pdf` (attachment) |
| GET | `/api/fonts/{sessionId}` | Analyze all fonts | -- | `FontInfo[]` |
| GET | `/api/fonts/{sessionId}/page/{n}` | Analyze fonts on page N | -- | `FontInfo[]` |
| GET | `/api/validate/{sessionId}` | Run validation | -- | `ValidationIssue[]` |
| GET | `/api/validate/{sessionId}/verapdf` | Run embedded veraPDF validation | -- | `{available, success, report, reportFormat, ...}` |
| GET | `/api/validate/{sessionId}/export` | Download validation report | -- | `text/plain` (attachment) |
| POST | `/api/client-errors` | Log client-side JavaScript errors | JSON payload from browser | `{logged: true}` |
| POST | `/api/edit/{sessionId}/add-field` | Add a form field | `FormFieldRequest` (JSON) | `{sessionId, pageCount, tree}` |

### DownloadProtectionRequest

```json
{
  "mode": "keep | custom | none",
  "userPassword": "...",
  "ownerPassword": "...",
  "algorithm": "AES-256 | AES-128 | RC4-128 | RC4-40"
}
```

`userPassword`, `ownerPassword`, and `algorithm` are only used when `mode` is `"custom"`. In `"keep"` mode the server uses the stored unlock password and original algorithm. In `"none"` mode the decrypted bytes are returned as-is.

### Error Responses

All errors return JSON: `{"error": "message"}` with appropriate HTTP status:
- `400` -- Invalid input (bad file type, wrong password, invalid field parameters)
- `404` -- Session not found (expired or invalid ID)
- `500` -- PDF processing error

### Session Management

- Sessions are stored in-memory with a 30-minute inactivity timeout
- Expired sessions are evicted every 60 seconds by a scheduled task
- Each session holds the raw PDF bytes and the parsed tree
- Sessions are created on upload and updated on edit operations

---

## Configuration

All configuration is in `application.properties`:

```properties
server.port=8080
spring.thymeleaf.cache=false
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB
pdfalyzer.session.timeout-minutes=30
logging.level.root=INFO
logging.level.io.pdfalyzer=DEBUG
```

---

## Project Structure

```
src/main/java/io/pdfalyzer/
├── PdfalyzerUiApplication.java          # Entry point (@EnableScheduling)
├── model/
│   ├── PdfNode.java                     # Tree node with id, icon, color, properties, bbox
│   ├── PdfSession.java                  # Session state (id, filename, bytes, tree, encryption)
│   ├── EncryptionInfo.java              # Encryption metadata (algorithm, key bits, permissions)
│   ├── DownloadProtectionRequest.java   # Export protection request DTO
│   ├── FontInfo.java                    # Font analysis result
│   ├── ValidationIssue.java             # Validation finding
│   └── FormFieldRequest.java            # Edit request DTO
├── service/
│   ├── SessionService.java              # In-memory session store with eviction
│   ├── PdfService.java                  # Upload, parse, unlock, download with re-encryption
│   ├── PdfStructureParser.java          # Deep COS-level PDF tree builder
│   ├── DocumentStructureTreeBuilder.java
│   ├── SemanticTreeBuilder.java
│   ├── AcroFormTreeBuilder.java
│   ├── CosNodeBuilder.java
│   ├── PageResourceBuilder.java
│   ├── FontInspectorService.java        # Font analysis per page
│   ├── FontDiagnosticsBuilder.java
│   ├── FontCollectionHelper.java
│   ├── ValidationService.java           # PDF structure/font/metadata validation
│   ├── PdfEditService.java              # Form field CRUD operations
│   ├── PdfFormFieldBuilder.java
│   └── PdfFieldOptionApplier.java
└── web/
    ├── HomeController.java              # Serves index + license templates
    ├── ApiController.java               # REST API endpoints
    ├── EditApiController.java           # Edit-specific REST endpoints
    ├── ResourceApiController.java       # Resource/attachment endpoints
    └── GlobalExceptionHandler.java      # Centralized error handling

src/main/resources/
├── application.properties
├── templates/
│   ├── index.html                       # Single-page Thymeleaf entry point
│   └── fragments/                       # Thymeleaf HTML fragments
│       ├── navbar.html
│       ├── main-content.html
│       ├── status-bar.html
│       ├── modals.html
│       └── scripts.html
└── static/
    ├── app.js                           # Client entry point (wires modules)
    ├── js/                              # JavaScript IIFE modules (one per namespace)
    │   ├── app-state.js                 # PDFalyzer namespace + shared state
    │   ├── app-utils.js                 # Utilities, toast, clearable inputs
    │   ├── app-tree.js / app-tree-render.js
    │   ├── app-viewer.js / app-viewer-render.js
    │   ├── app-tabs.js / app-tabs-fonts.js / app-tabs-font-detail.js
    │   ├── app-tabs-glyph.js / app-tabs-glyph-canvas.js / app-tabs-glyph-viewer.js / app-tabs-glyph-ui.js
    │   ├── app-tabs-validation.js       # Validation rendering + smart issue explainer
    │   ├── app-edit-mode.js / app-edit-field.js / app-edit-options.js
    │   ├── app-edit-designer.js         # Snap-to-grid, copy/paste, align/distribute/size
    │   ├── app-theme.js                 # Color theme picker (100 themes, status-bar panel, localStorage)
    │   └── app-upload.js / app-search.js / app-zoom.js / app-keyboard.js / app-export.js / ...
    ├── colors.css                       # Color system: 10 base vars, derived aliases, 100 themes
    ├── styles.css                       # Layout, navbar, modals, theme-picker widget styles
    ├── styles-tree.css                  # Tree pane, validation, status bar, image meta
    ├── styles-fonts.css                 # Font table, font focus card, font detail tabs
    ├── styles-glyph.css                 # Glyph canvas, hero, usage panel, diagnostic badges
    ├── styles-editor.css                # Edit mode, form-field handles, pending panel, modals
    ├── styles-common.css                # Animations, scrollbar, buttons, toasts, COS badges
    └── logo.svg                         # Application logo

src/test/java/io/pdfalyzer/
└── ...                                  # Unit tests (66 tests, 0 failures)
```

---

## Resources

- [Apache PDFBox](https://pdfbox.apache.org/)
- [OpenPDF (GitHub)](https://github.com/LibrePDF/OpenPDF)
- [pdf.js (Mozilla)](https://mozilla.github.io/pdf.js/)
- [Thymeleaf](https://www.thymeleaf.org/)
- [Bootstrap / Bootswatch](https://bootswatch.com/)
