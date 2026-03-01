# PDFalyzer UI

PDFalyzer UI is a Spring Boot web application for inspecting, validating, and editing PDF internals through an interactive browser UI.

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
- Optional veraPDF execution via API with in-UI rendering (HTML/XML/plain output handling).
- veraPDF report can be exported to HTML/PDF from the Validation tab.

### Editing capabilities

- Form field creation workflow (Text, Checkbox, Combo, Radio, Signature).
- Draw rectangle on page, configure field id/options, queue changes, then save in batch.
- Field options updates for selected fields.
- Field rectangle updates and field deletion API support.
- COS editing from tree:
  - edit primitive values,
  - add dictionary/array entries,
  - remove entries,
  - remove image/resource dictionary entries.

### Resource and attachment handling

- Preview/download stream resources (`/api/resource/...`).
- Preview/download embedded attachments (`/api/attachment/...`).

### Keyboard shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+O` | Open PDF picker |
| `Ctrl+F` | Focus search |
| `Ctrl+S` | Download current PDF |
| `Ctrl+E` | Preselect Text field placement mode |
| `Esc` | Clear focused search input |
| `1`..`7` | Switch tabs |

## Main API Endpoints

### Session and tree

- `POST /api/upload`
- `GET /api/tree/{sessionId}`
- `GET /api/tree/{sessionId}/search?q=...`
- `GET /api/tree/{sessionId}/export`
- `GET /api/tree/{sessionId}/raw-cos`

### PDF bytes/download

- `GET /api/pdf/{sessionId}`
- `GET /api/pdf/{sessionId}/download`

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

## Known Limitations

- Session storage is in-memory only (no persistence across restart).
- Not all PDF features are fully editable; focus is forms + COS-level manipulations.
- Some complex content-stream level mappings may be partial depending on PDF structure.

## Project Layout

- Backend: `src/main/java/io/pdfalyzer/...`
- Frontend static assets: `src/main/resources/static/...`
- Thymeleaf template: `src/main/resources/templates/index.html`

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
| POST | `/api/upload` | Upload and parse a PDF | `multipart/form-data` with `file` field | `{sessionId, filename, pageCount, tree}` |
| GET | `/api/tree/{sessionId}` | Get full structure tree | -- | `PdfNode` (JSON) |
| GET | `/api/tree/{sessionId}/search?q=` | Search tree nodes | Query param `q` | `PdfNode[]` |
| GET | `/api/tree/{sessionId}/export` | Download tree as JSON | -- | `PdfNode` (attachment) |
| GET | `/api/pdf/{sessionId}` | Get raw PDF bytes | -- | `application/pdf` |
| GET | `/api/pdf/{sessionId}/download` | Download PDF as file | -- | `application/pdf` (attachment) |
| GET | `/api/fonts/{sessionId}` | Analyze all fonts | -- | `FontInfo[]` |
| GET | `/api/fonts/{sessionId}/page/{n}` | Analyze fonts on page N | -- | `FontInfo[]` |
| GET | `/api/validate/{sessionId}` | Run validation | -- | `ValidationIssue[]` |
| GET | `/api/validate/{sessionId}/verapdf` | Run embedded veraPDF validation | -- | `{available, success, report, reportFormat, ...}` |
| GET | `/api/validate/{sessionId}/export` | Download validation report | -- | `text/plain` (attachment) |
| POST | `/api/client-errors` | Log client-side JavaScript errors | JSON payload from browser | `{logged: true}` |
| POST | `/api/edit/{sessionId}/add-field` | Add a form field | `FormFieldRequest` (JSON) | `{sessionId, pageCount, tree}` |

### Error Responses

All errors return JSON: `{"error": "message"}` with appropriate HTTP status:
- `400` -- Invalid input (bad file type, invalid field parameters)
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
│   ├── PdfSession.java                  # Session state (id, filename, bytes, tree, pageCount)
│   ├── FontInfo.java                    # Font analysis result
│   ├── ValidationIssue.java             # Validation finding
│   └── FormFieldRequest.java            # Edit request DTO
├── service/
│   ├── SessionService.java              # In-memory session store with eviction
│   ├── PdfStructureParser.java          # Deep COS-level PDF tree builder
│   ├── PdfService.java                  # Upload, parse, search, session management
│   ├── FontInspectorService.java        # Font analysis per page
│   ├── ValidationService.java           # PDF structure/font/metadata validation
│   └── PdfEditService.java              # Form field creation
└── web/
    ├── HomeController.java              # Serves index template
    ├── ApiController.java               # REST API endpoints
    └── GlobalExceptionHandler.java      # Centralized error handling

src/main/resources/
├── application.properties
├── templates/
│   └── index.html                       # Single-page Thymeleaf template
└── static/
    ├── app.js                           # Client-side application (~850 lines)
    ├── styles.css                       # Custom CSS with dark theme
    └── logo.svg                         # Application logo

src/test/java/io/pdfalyzer/
└── PdfServiceTest.java                  # Unit tests (5 tests)
```

---

## Known Limitations

1. **No revision/incremental update support** -- The tree shows the merged state of all revisions; individual revisions cannot be inspected or compared
2. **No encrypted PDF support** -- Password-protected PDFs will fail to parse
3. **Memory-bound sessions** -- Large PDFs are held entirely in memory; no disk-based fallback
4. **Single-user per session** -- Sessions are independent; no collaborative features
5. **Zoomable PDF viewer** -- Use Ctrl+mousewheel to zoom in/out; click the expand-arrows button to toggle auto-fit width/height modes
6. **CDN dependency** -- pdf.js is loaded from cdnjs.cloudflare.com; offline use requires bundling
7. **No content stream parsing** -- Text, graphics operators, and inline images within content streams are not exposed in the tree
8. **No full PDF/A conformance** -- Validation covers common issues but is not a complete PDF/A validator (consider VeraPDF for full conformance)

---

## Future Enhancements

- Signature validation and certificate chain analysis
- Full PDF/A, PDF/X, PDF/UA conformance via VeraPDF integration
- Zoom controls and fit-to-width/fit-to-height for the PDF viewer (available)- Content stream parsing and text extraction display
- Side-by-side PDF comparison / visual diff
- Incremental update / revision viewer
- Dark/light theme toggle
- Plugin architecture for custom analyzers

---

## Resources

- [Apache PDFBox](https://pdfbox.apache.org/)
- [OpenPDF (GitHub)](https://github.com/LibrePDF/OpenPDF)
- [pdf.js (Mozilla)](https://mozilla.github.io/pdf.js/)
- [Thymeleaf](https://www.thymeleaf.org/)
- [Bootstrap / Bootswatch](https://bootswatch.com/)
