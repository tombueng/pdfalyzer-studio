# PDFalyzer UI

A Spring Boot web application for deep inspection, analysis, validation, and editing of PDF documents through a rich graphical interface.

## Quick Start

### Prerequisites

- Java 17 or later
- Maven 3.8+

### Build & Run

```bash
cd pdfalyzer-ui
mvn spring-boot:run
```

Open `http://localhost:8080` in a browser and upload a PDF file.

### Run Tests

```bash
mvn test
```

---

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Backend | Spring Boot (Java, Spring MVC) | 3.1.0 |
| Template Engine | Thymeleaf | (via Spring Boot) |
| PDF Parsing | Apache PDFBox | 3.0.0 |
| PDF Editing | Apache PDFBox + OpenPDF | 3.0.0 / 1.3.30 |
| Client-side PDF Rendering | pdf.js | 3.11.174 (CDN) |
| CSS Framework | Bootstrap 5 (Bootswatch Cyborg dark theme) | 5.3.0 |
| Icons | Font Awesome | 6.4.0 |
| Frontend | Vanilla JavaScript (no framework) | ES5 |

### Architecture

The application uses a hybrid rendering approach:

- **Thymeleaf** serves the initial HTML shell (single page at `/`)
- **REST API** (`/api/*`) returns JSON for all dynamic data
- **Client-side JavaScript** handles all user interaction, tree rendering, PDF viewing, and state management
- **In-memory sessions** store uploaded PDFs keyed by UUID (no database required)

---

## Features

### 1. PDF Upload & Viewing

Upload a PDF via the navbar button, the splash screen button, or drag-and-drop onto the viewer pane.

**What it does:**
- Accepts PDF files up to 100 MB
- Validates file type both client-side (extension/MIME) and server-side (`.pdf` extension required)
- Renders all pages sequentially using pdf.js with fade-in animation
- Each page is displayed as a separate canvas element in a scrollable viewer
- Page labels appear at the bottom-right corner of each page

**What it cannot do:**
- Does not support password-protected/encrypted PDFs (PDFBox will throw an error)
- Does not render PDFs server-side; all rendering is client-side via pdf.js
- Does not support loading PDFs from URLs or cloud storage (upload only)
- Adjustable rendering scale with manual zoom (Ctrl+wheel) and auto-fit modes

### 2. PDF Structure Tree

After upload, the right panel displays the full internal structure of the PDF parsed from its COS (Carousel Object System) object graph.

**Tree hierarchy:**
```
Document Catalog
├── Document Info (title, author, creator, producer, dates)
├── Pages (N)
│   └── Page 1
│       ├── Resources
│       │   ├── Fonts (with name, type, embedded status, subset info)
│       │   └── Images (with dimensions, color space, BPC)
│       └── Annotations (with subtype, bounding box, contents)
├── AcroForm (form fields with type, name, value, required/readonly)
└── Bookmarks (hierarchical outline items)
```

**What it does:**
- Parses the complete PDF structure using PDFBox's COS-level API
- Displays type-specific icons and colors for each node type
- Shows metadata properties in a collapsible detail panel per node
- Nodes are expandable/collapsible with smooth toggle animation (circular reference nodes include a jump link that navigates to the original object)
- Clicking a node with a bounding box (annotations, form fields) highlights the corresponding rectangle on the PDF page and scrolls the viewer to that page
- Clicking a node associated with a page (fonts, images, resources) scrolls the viewer to that page
- Handles cyclic references in the COS object graph safely
- Dictionary/array entries in the tree can now be added or removed via inline forms; primitive values are editable inline (click pencil icon)

**What it cannot do:**
- Does not parse raw COS dictionary/array structures beyond the high-level objects (catalog, pages, fonts, images, annotations, forms, bookmarks)
- Does not display content streams (the actual drawing operators)
- Does not parse cross-reference tables or show revision history
- Does not support incremental update/revision filtering (all revisions shown as merged)

### 3. Shortcut Tabs

Six tabs above the tree panel provide quick access to specific sections:

| Tab | Key | What it shows |
|-----|-----|--------------|
| Structure | `1` | Full tree (default) |
| Forms | `2` | AcroForm subtree only (form fields hierarchy) |
| Bookmarks | `3` | Document outline / bookmarks |
| Pages | `4` | Pages subtree with per-page resources |
| Fonts | `5` | Font analysis table (see Font Inspector) |
| Validation | `6` | Validation results (see Validator) |

### 4. Search

The search bar in the navbar filters tree nodes by name or property values.

**What it does:**
- Debounced (300ms) to avoid excessive API calls while typing
- Searches across node names and all property values (case-insensitive)
- Displays matching nodes as a flat list with result count
- Clearing the search restores the full tree

**What it cannot do:**
- Does not search inside PDF text content (only structural metadata)
- Does not highlight search terms within the tree
- Does not search within font tables or validation results

### 5. Interactive Selection (PDF <-> Tree)

**Tree -> PDF:** Clicking a tree node that has a `boundingBox` (annotations, form widgets) draws a pulsing highlight rectangle over the corresponding location on the PDF page and scrolls the viewer to that page.

**PDF -> Tree:** Clicking on the PDF viewer checks if the click coordinates fall inside any tree node's bounding box. If a match is found, that node is selected and scrolled into view in the tree panel.

**What it cannot do:**
- Cannot highlight arbitrary objects that don't have explicit bounding boxes (e.g., fonts, images referenced in content streams)
- Coordinate mapping depends on the annotation/widget having a valid Rectangle entry

### 6. Font Inspector

Accessible via the **Fonts** tab. Calls `GET /api/fonts/{sessionId}` which analyzes all fonts across all pages.

**Analysis includes:**
- Font name and type (Type1Font, TrueTypeFont, Type0Font, Type3Font, CIDFont)
- Embedded status (green check or red X)
- Subset status (detected by 6-letter prefix + `+` in font name)
- Encoding information (from COS dictionary)
- Page number where the font is used
- Issue detection:
  - Non-embedded fonts flagged with warning
  - Type3 fonts flagged for potential cross-platform rendering issues

**What it cannot do:**
- Does not display the actual character map or glyph table
- Does not detect specific missing glyphs (would require rendering each character)
- Does not visualize font usage areas on the page
- Does not provide font repair or re-embedding tools
- Fonts used in XObject Form content may not be detected (only page-level resources are scanned)

### 7. Super Validator

Accessible via the **Validation** tab. Click "Run Validation" to analyze the PDF.

**Validation rules:**

| Rule ID | Severity | What it checks |
|---------|----------|---------------|
| META-001 | WARNING | XMP metadata stream presence (PDF/A requirement) |
| META-002 | INFO | Document title presence |
| META-003 | INFO | Producer information presence |
| FONT-001 | ERROR | Font embedding (PDF/A requires all fonts embedded) |
| FONT-002 | WARNING | ToUnicode CMap presence (needed for text extraction) |
| FONT-ERR | ERROR | Font loading failures |
| PAGE-001 | ERROR | Document has zero pages |
| PAGE-002 | ERROR | Page missing MediaBox |
| ANNOT-001 | WARNING | Annotation missing rectangle |
| ANNOT-002 | WARNING | Annotation missing appearance stream (PDF/A requirement) |

**Results display:**
- Grouped by severity (errors, then warnings, then info)
- Summary counts at the top
- Each issue shows rule ID, message, spec reference, and location
- Exportable as a text report via the "Export Report" button

**What it cannot do:**
- Does not perform full PDF/A or PDF/X conformance validation (would require VeraPDF or PDFBox Preflight)
- Does not validate color spaces, ICC profiles, or transparency
- Does not validate digital signatures
- Does not check for linearization or tagged PDF structure
- Does not highlight issues inline on the PDF viewer

### 8. PDF Editing (Form Fields)

Toggle edit mode with the pencil button in the navbar (or `Ctrl+E`).

**Workflow:**
1. Click the edit button to enter edit mode
2. Select a field type from the toolbar (Text, Checkbox, Combo, Radio, Signature)
3. Draw a rectangle on the PDF page where you want the field
4. Enter a field name when prompted
5. The PDF is modified server-side, re-rendered, and the tree refreshed

**Supported field types:**
- **Text** -- PDTextField with Helvetica 10pt default appearance
- **Checkbox** -- PDCheckBox with appearance stream
- **Combo** -- PDComboBox (comma-separated choices via options)
- **Radio** -- PDRadioButton with appearance stream
- **Signature** -- PDSignatureField (empty, ready for signing)

**What it does:**
- Creates proper PDF form fields using PDFBox's PDAcroForm API
- Sets up default resources (Helvetica font) if no AcroForm exists
- Generates appearance streams for visibility in PDF viewers
- Validates field name, page index, and dimensions before creation
- Downloads the modified PDF via the download button

**What it cannot do:**
- Cannot edit or delete existing form fields
- Cannot modify field properties after creation
- Cannot add non-form annotations (stamps, highlights, comments)
- Cannot edit page content (text, images, graphics)
- Cannot set up actual digital signatures (only creates the field placeholder)
- Combo box choices must be configured via the API directly (no UI for options)

### 9. Export & Download

| Action | Button | Shortcut | Endpoint |
|--------|--------|----------|----------|
| Download PDF | Download icon in navbar | `Ctrl+S` | `GET /api/pdf/{id}/download` |
| Download resource | click download icon next to tree node | n/a | `GET /api/resource/{id}/{obj}/{gen}` (attachment or inline) |
| Export tree as JSON | Export icon in navbar | -- | `GET /api/tree/{id}/export` |
| Export validation report | Button in Validation tab | -- | `GET /api/validate/{id}/export` |

The download feature is especially useful after editing: add form fields, then download the modified PDF.

### 10. Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+O` | Open file picker |
| `Ctrl+F` | Focus search bar |
| `Ctrl+S` | Download current PDF |
| `Ctrl+E` | Toggle edit mode |
| `Escape` | Exit edit mode or clear search |
| `1`-`6` | Switch between tabs |

### 11. UI Features

- **Dark theme** -- Bootswatch Cyborg with custom CSS variables
- **Resizable split pane** -- Drag the divider between PDF viewer and tree panel
- **Drag-and-drop upload** -- Drop PDF files onto the viewer area
- **Loading skeletons** -- Automatically shown after 1-second delay on any API request
- **Toast notifications** -- Appear for upload success/failure, validation results, edit confirmations
- **Status bar** -- Shows filename, page count, session status, and loading indicator
- **Responsive layout** -- Stacks vertically on screens under 768px
- **Custom SVG logo** -- Magnifying glass over PDF document

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
| GET | `/api/validate/{sessionId}/export` | Download validation report | -- | `text/plain` (attachment) |
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
