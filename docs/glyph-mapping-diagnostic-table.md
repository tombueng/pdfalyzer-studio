# Glyph Mapping Diagnostic Table

This table helps visualize font encoding and mapping problems in PDFs, especially cases with embedded subset fonts and missing or mismatched glyphs. It is inspired by the concept of a "missing glyph definition table" and is useful for manual inspection and diagnostics.

## Table Columns

| CID/GID | Unicode Mapping | Glyph Name | Font Subset Status | Rendered Preview | Diagnostic Status |
|---------|----------------|------------|--------------------|------------------|------------------|
| 100     | U+0041         | A          | Embedded           | (A)              | OK               |
| 101     |                |            | Embedded           | (missing)        | No Unicode mapping|
| 102     | U+0042         | B          | Missing            | (missing)        | Glyph not in font |
| ...     | ...            | ...        | ...                | ...              | ...              |

## Diagnostic Status Examples
- **OK**: Glyph is present in font subset and has a Unicode mapping.
- **No Unicode mapping**: Glyph is present in font subset but missing in ToUnicode.
- **Glyph not in font**: CID/GID is referenced but not present in embedded font subset.
- **Encoding mismatch**: CID/GID does not match font encoding or Unicode mapping.

## Naming Suggestions
- Missing Glyph Definition Table
- Glyph Mapping Diagnostic Table
- Font Encoding Problem Table
- Glyph Reference Consistency Table
- Font Subset Mapping Table

## Implementation Steps
1. Extend diagnostics to collect all glyphs referenced in the PDF.
2. For each glyph, check:
    - Is it present in the embedded font subset?
    - Is there a Unicode mapping in ToUnicode?
    - Is the encoding correct?
3. Visualize as a table in the UI or as a diagnostic report.

## Example Usage
This table can be generated for each font in a PDF to help users and developers quickly spot encoding and mapping issues that may not be detected by standard diagnostics.
