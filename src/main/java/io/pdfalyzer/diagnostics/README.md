# PDF Diagnostics Framework

## Overview

The PDF Diagnostics Framework is a comprehensive system for identifying and diagnosing common PDF issues. It provides:

- **5 Core Diagnostics** for the most common PDF problems
- **Detection Capabilities** using Apache PDFBox and analysis
- **Repair  Suggestions** with detailed fix recommendations
- **JSON Output** for integration with UI and reporting tools
- **Extensible Design** for adding new diagnostics
- **Detailed Documentation** with ISO 32000 spec references

## The 5 Core PDF Issues

### 1. Missing Glyphs / Unembedded Fonts

**Issue ID:** `MISSING_GLYPHS`

**Problem:**  
Characters in the PDF cannot be displayed because fonts are not properly embedded or character encodings don't map correctly.

**Impact:**
- Text appears as boxes or substituted fonts change layout
- Different rendering on different systems (Windows vs Mac vs Linux)
- Text extraction fails for certain characters
- Accessibility compromised for screen readers

**Detectability:**  ✅ Full detection via PDFBox
- Can identify unembedded fonts
- Can check standard 14 fonts
- Can detect missing ToUnicode CMaps

**Fixability:** ⚠️ Partial (see recommendations)
- PDFBox cannot fully re-embed fonts (limitation)
- Requires professional tools (Adobe Acrobat, LibreOffice, iText)
- Re-saving with font embedding enabled

**Severity:** MAJOR

**References:**
- ISO 32000-1:2008, Section 9.7 (Fonts)
- Adobe PDF Reference 1.7, Chapter 5 (Text and Graphics State)

---

### 2. Broken Cross-Reference Table (xref)

**Issue ID:** `BROKEN_XREF`

**Problem:**  
The xref table contains incorrect byte offsets, preventing PDF readers from locating objects in the file.

**Impact:**
- Objects cannot be found or parsed correctly
- Document becomes unreadable or partially readable
- Text extraction fails
- Incremental updates become impossible

**Root Causes:**
- Manual PDF editing without updating xref
- Incorrect file concatenation
- Corrupted PDF generators
- Truncated xref section

**Detectability:** ✅ Moderate detection
- PDFBox auto-repairs during loading
- If it loads successfully, xref is recoverable
- Can validate object accessibility

**Fixability:** ✅ Possible via PDFBox + QPDF
- PDFBox auto-repairs on load
- Saving document writes valid xref
- QPDF can rebuild from scratch: `qpdf --fix-qdf input.pdf output.pdf`

**Severity:** CRITICAL

**References:**
- ISO 32000-1:2008, Section 7.5.4 (Cross-Reference Tables)
- ISO 32000-1:2008, Section 7.5.5 (Cross-Reference Streams)
- Adobe PDF Reference 1.7, Section 7.5 (File Structure)

---

### 3. Corrupted or Truncated File

**Issue ID:** `CORRUPTED_TRUNCATED`

**Problem:**  
The PDF file is incomplete or has corrupted data due to:
- Incomplete download
- Aborted file transfer
- Truncation after EOF marker
- Storage/disk errors

**Impact:**
- File cannot be opened
- Some pages missing
- Content streams incomplete
- Incremental updates inaccessible

**Detectability:** ✅ Full detection
- Check for EOF markers
- Verify file structure completeness
- Check for unclosed streams
- Validate trailer

**Fixability:** ✅ Often fixable
- Simple truncation: append `%%EOF`
- Use QPDF to rebuild: `qpdf --fix-qdf input.pdf output.pdf`
- Use Ghostscript for recovery
- Re-download if incomplete

**Severity:** CRITICAL

**References:**
- ISO 32000-1:2008, Section 7.5.1 (File Structure)
- ISO 32000-1:2008, Section 7.5.2 (File Header and Footer)
- Adobe PDF Reference 1.7, Section 7.5

---

### 4. Missing or Incomplete AcroForm Structure

**Issue ID:** `MISSING_ACROFORM`

**Problem:**  
PDF has interactive form fields but lacks proper AcroForm dictionary structure, making forms non-functional.

**Impact:**
- Form fields don't respond to user input
- Field values cannot be submitted
- PDF readers cannot parse form structure
- Accessibility issues for screen readers
- Data cannot be extracted from filled forms

**Root Causes:**
- Damaged or corrupted AcroForm dictionary
- Missing references to form fields
- Incomplete field inheritance
- Malformed appearance (DA) resources

**Detectability:** ✅ Good detection
- Check for /AcroForm in catalog
- Verify /Fields array exists
- Check widget/field references
- Validate required properties

**Fixability:** ⚠️ Partial (requires iText or Adobe)
- PDFBox can create basic AcroForm
- iText can fully rebuild with properties
- Adobe Acrobat Pro can detect/repair
- May need form re-creation in authoring tool

**Severity:** MAJOR

**References:**
- ISO 32000-1:2008, Section 12.7 (Interactive Forms / AcroForms)
- Adobe PDF Reference 1.7, Chapter 12 (Interactive Features)
- AcroForm Specification (Adobe proprietary)

---

### 5. Zip Bomb / Exponential Decompression Attack

**Issue ID:** `ZIP_BOMB`

**Problem:**  
PDF streams have nested compression filters (FlateDecode chains) that expand to enormous sizes when decompressed, causing Denial of Service.

**Impact:**
- Crash PDF reader / application
- Memory exhaustion
- CPU exhaustion during decompression
- System becomes unresponsive
- Potential security vulnerability

**Technical Details:**
- Compressed 5 KB file can expand to terabytes
- Expansion ratio can reach 1 trillion times
- Uses nested FlateDecode filters
- Highly repetitive compressed data

**Detectability:** ⚠️ Limited (requires safe analysis)
- Can identify nested filter chains
- Can estimate expansion ratios
- Cannot fully decompress without risk
- Requires sandboxed processing

**Fixability:** ❌ Not fixable (intentional attack)
- Cannot "fix" malicious PDFs
- Can only mitigate risk
- Reject or sandbox for processing
- Use size limits during decompression
- QPDF has built-in protection

**Severity:** CRITICAL

**CVE:** CVE-2025-55197 (PyPDF vulnerability)

**References:**
- ISO 32000-1:2008, Section 7.4.2 (Streams and Filters)
- RFC 1951 (DEFLATE Compressed Data Format)
- Zip bomb article: https://en.wikipedia.org/wiki/Zip_bomb

---

## Architecture

### Class Structure

```
io.pdfalyzer.diagnostics/
├── PdfIssueDiagnostic.java          (Interface for all diagnostics)
├── PdfDiagnosticsRegistry.java      (Manager, coordinates all diagnostics)
├── DiagnosticsJsonSerializer.java   (JSON serialization)
├── PdfDiagnosticsExample.java       (Usage example)
├── model/
│   └── PdfFinding.java              (JSON-serializable finding)
└── issues/
    ├── MissingGlyphsDiagnostic.java
    ├── BrokenXrefDiagnostic.java
    ├── CorruptedTruncatedDiagnostic.java
    ├── MissingAcroFormStructureDiagnostic.java
    └── ZipBombDecompressionDiagnostic.java
```

### Key Classes

**PdfIssueDiagnostic (Interface)**
- `diagnose(PDDocument)` - Detect if issue exists
- `attemptFix(PDDocument)` - Try to fix the issue
- `generateDemoPdf()` - Create demo PDF with the issue
- `getDetailedDescription()` - Long-form documentation
- Spec/community references

**PdfFinding (Data Model)**
- Serializable to JSON
- Contains: issueId, severity, description, affected objects, recommendations
- Indicates if issue was detected
- Notes if fix may lose data
- Includes spec and StackOverflow references

**PdfDiagnosticsRegistry**
- Registers all diagnostics
- Runs all diagnostics in one pass: `diagnoseAll()`
- Attempts fixes: `fixAll()`
- Generates summary report
- Can look up by issue ID

**DiagnosticsJsonSerializer**
- Serialize findings to JSON
- Deserialize JSON back to objects
- Pretty-printing with indentation
- Generates complete diagnostic reports

---

## Usage Examples

### Basic Usage

```java
// Load PDF
PDDocument document = Loader.loadPDF(new File("problematic.pdf"));

// Create registry with all diagnostics
PdfDiagnosticsRegistry registry = new PdfDiagnosticsRegistry();

// Run all diagnostics
List<PdfFinding> findings = registry.diagnoseAll(document);

// Check results
for (PdfFinding finding : findings) {
    if (finding.isDetected()) {
        System.out.println("Found: " + finding.getIssueName());
        System.out.println("Severity: " + finding.getSeverity());
        System.out.println("Affected: " + finding.getAffectedObjects());
    }
}
```

### JSON Output

```java
DiagnosticsJsonSerializer serializer = new DiagnosticsJsonSerializer();
String json = serializer.serializeFindings(findings);
System.out.println(json);
```

### Attempt Fixes

```java
List<PdfFinding> fixResults = registry.fixAll(document);
document.save("output_fixed.pdf");
```

### Get Summary

```java
Map<String, Object> summary = registry.getSummary(findings);
System.out.println("Status: " + summary.get("overallStatus"));
System.out.println("Critical Issues: " + summary.get("criticalCount"));
```

---

## JSON Output Format

Each finding is serialized with:

```json
{
  "issue_id": "MISSING_GLYPHS",
  "issue_name": "Missing Glyphs / Unembedded Fonts",
  "severity": "MAJOR",
  "description": "...",
  "is_detected": true,
  "affected_objects": ["Page 1, Font: Arial", ...],
  "fix_may_lose_data": false,
  "details": {
    "unembeddedFontCount": 3,
    "fontAnalysis": [...]
  },
  "fix_recommendations": [...],
  "specification_references": [...],
  "stack_overflow_references": [...]
}
```

---

## Integration with pdfalyzer-ui

The diagnostics framework is designed to be integrated into pdfalyzer-ui as follows:

### Phase 1: Core Framework (Current)
- ✅ All 5 diagnostic classes  
- ✅ Detection implementations
- ✅ JSON serialization
- ✅ Documentation

### Phase 2: UI Integration (Future)
- Create REST endpoints for diagnostics
- Display findings in web interface
- Show severity indicators
- Link to specification docs
- Offer manual fix suggestions

### Phase 3: Demo PDF Generation (Future)
- Implement `generateDemoPdf()` for each issue
- Create visual examples of problems
- Use iText or PDFBox for creation
- Embed issue descriptions in PDF

### Phase 4: Advanced Features (Future)
- Batch diagnostics on multiple PDFs
- Export reports (HTML, PDF)
- Trend analysis
- Custom diagnostic rules

---

## Adding New Diagnostics

To add a new diagnostic:

1. **Create class implementing PdfIssueDiagnostic**
   ```java
   public class NewIssueDiagnostic implements PdfIssueDiagnostic {
       private static final String ISSUE_ID = "NEW_ISSUE";
       // Implement all methods...
   }
   ```

2. **Register in PdfDiagnosticsRegistry**
   ```java
   registerDiagnostic(new NewIssueDiagnostic());
   ```

3. **Implement required methods**
   - `getIssueId()` - Unique identifier
   - `getIssueName()` - Human-readable name
   - `getDetailedDescription()` - Full documentation
   - `diagnose()` - Detection logic
   - `attemptFix()` - Fix logic
   - `generateDemoPdf()` - Demo creation
   - `getSpecificationReferences()` - ISO/Adobe specs
   - `getCommunityReferences()` - External links

---

## Dependencies

The framework only requires:
- **Apache PDFBox** (3.0+) - PDF parsing and manipulation
- **Jackson** (2.15+) - JSON serialization

No external PDF tools required (QPDF, Ghostscript) for diagnostics themselves, though they're recommended for fixes.

---

## Limitations & Future Work

**Current Limitations:**
1. Font re-embedding not supported (requires iText)
2. Full AcroForm reconstruction limited
3. Demo PDF generation not implemented
4. Zip bomb detection is heuristic-based
5. No programmatic fixes for zip bombs (by design)

**Future Enhancements:**
1. iText 7 integration for advanced repairs
2. Machine learning for anomaly detection
3. Batch processing utilities
4. Performance analysis
5. Recommendations engine
6. Custom rule definitions
7. Report generation (HTML, PDF)
8. Database for issue tracking

---

## Testing

To test the diagnostics framework:

```bash
# Run example on a PDF file
java io.pdfalyzer.diagnostics.PdfDiagnosticsExample /path/to/pdf/file.pdf

# Create test PDFs with known issues
# Use existing problematic PDFs from test suite
```

---

## References

### PDF Specifications
- ISO 32000-1:2008 - PDF 1.7 (free access)
- ISO 32000-2:2020 - PDF 2.0 standard
- Adobe PDF Reference 1.7 (deprecated but useful)

### Tools Mentioned
- **QPDF**: http://qpdf.sourceforge.net/ (open source PDF repair)
- **Ghostscript**: https://www.ghostscript.com/ (PDF rendering)
- **iText**: https://itext.com/ (PDF library with full features)
- **Apache PDFBox**: https://pdfbox.apache.org/ (Java PDF tool)

### Related CVEs
- CVE-2025-55197 - PyPDF zip bomb vulnerability
- Similar ZIP bomb research available

---

## Contact & Support

Questions or suggestions for improvements?
- Check existing issues in pdfalyzer-ui GitHub
- Refer to PDF specification sections for detailed technical questions
- Review community references in each diagnostic

---

**Last Updated:** March 2, 2026  
**Version:** 1.0 (Beta)  
**Status:** Ready for integration into pdfalyzer-ui
