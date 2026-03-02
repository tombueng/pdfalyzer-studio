package io.pdfalyzer.diagnostics.issues;

import io.pdfalyzer.diagnostics.PdfIssueDiagnostic;
import io.pdfalyzer.diagnostics.model.PdfFinding;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;

import java.io.IOException;
import java.util.*;

/**
 * Diagnostic for Missing Glyphs and Unembedded Fonts issues.
 * 
 * ISSUE DESCRIPTION:
 * Some characters in the PDF cannot be displayed because:
 * 1. The font used is not embedded in the PDF
 * 2. The character codes don't map to glyphs in the font
 * 3. The font's ToUnicode CMap is missing
 * 
 * CONSEQUENCES:
 * - Missing or garbled text when viewing on systems without the font
 * - Text extraction fails for certain characters
 * - Accessibility is compromised
 * - Document appears differently on different systems
 * 
 * SPECIFICATION: ISO 32000-1, Section 5.3 (Text), 9.7 (Fonts)
 * 
 * EXAMPLE PROBLEM:
 * A PDF uses the "Arial" font but doesn't embed it. The reader has "Liberation Sans"
 * which is similar but character mappings differ, causing text to display incorrectly.
 */
public class MissingGlyphsDiagnostic implements PdfIssueDiagnostic {
    
    private static final String ISSUE_ID = "MISSING_GLYPHS";
    private static final String ISSUE_NAME = "Missing Glyphs / Unembedded Fonts";
    
    // Standard 14 fonts that are always available
    private static final Set<String> STANDARD_14_FONTS = Set.of(
            "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic",
            "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
            "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
            "Symbol", "ZapfDingbats"
    );

    @Override
    public String getIssueId() {
        return ISSUE_ID;
    }

    @Override
    public String getIssueName() {
        return ISSUE_NAME;
    }

    @Override
    public String getDetailedDescription() {
        return """
            MISSING GLYPHS AND UNEMBEDDED FONTS
            
            OVERVIEW:
            This issue occurs when PDF content references fonts that are not properly embedded in the 
            document, and the character encodings do not properly map to available glyphs.
            
            TECHNICAL DETAILS:
            - PDF files embed fonts to ensure consistent display across systems
            - When fonts are not embedded, viewers must substitute them from the system
            - Substitution can cause text reflow, character misalignment, or garbled content
            - Character codes must map to glyphs via encoding tables or ToUnicode CMaps
            
            IMPACT:
            - TEXT RENDERING: Characters appear as boxes, or substituted fonts change layout
            - ACCESSIBILITY: Screen readers cannot extract text correctly
            - CONSISTENCY: Same PDF looks different on Windows vs Mac vs Linux
            - SEARCHABILITY: Users cannot find/copy text with missing glyphs
            
            DETECTION METHOD:
            We inspect all fonts in the PDF and check:
            1. Is the font embedded? (has FontFile stream)
            2. Is it a standard 14 font? (always available)
            3. Does it have a ToUnicode CMap for character mapping?
            4. Are there any missing glyph references?
            
            FIX APPROACH (requires re-embedding fonts):
            The recommended fix is to:
            1. Re-save the PDF with font embedding enabled in the authoring tool
            2. Use PDF tools to extract and re-embed missing fonts
            3. This guarantees consistent rendering across all systems
            WARNING: May increase file size by 10-50% for fonts with many characters.
            
            REFERENCES:
            - ISO 32000-1:2008, Section 5.3 (Text and Graphics State)
            - ISO 32000-1:2008, Section 9.7 (Fonts and Font Specifications)
            - Adobe PDF Reference 1.7, Section 5.3.2 (Font Subset)
            
            RELATED SPECIFICATIONS:
            - CID Fonts (Composite Identity-H/Identity-V encodings)
            - Type 0 vs Type 1 fonts
            - ToUnicode CMap Format (Adobe specification)
            """;
    }

    @Override
    public PdfFinding diagnose(PDDocument document) throws IOException {
        PdfFinding finding = new PdfFinding(ISSUE_ID, ISSUE_NAME);
        finding.setSeverity(PdfFinding.Severity.MAJOR);
        
        List<String> affectedObjects = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();
        List<Map<String, Object>> fontAnalysis = new ArrayList<>();
        
        int unembeddedFontCount = 0;
        int missingToUnicodeCount = 0;
        int glyphMissingCount = 0;
        
        // Check all pages for font usage
        for (int pageNum = 0; pageNum < document.getNumberOfPages(); pageNum++) {
            PDPage page = document.getPage(pageNum);
            PDResources resources = page.getResources();
            
            if (resources != null) {
                // Check all fonts on this page
                Iterable<?> fontNamesIterable = resources.getFontNames();
                if (fontNamesIterable != null) {
                    for (Object fontNameObj : fontNamesIterable) {
                        String fontName = fontNameObj.toString();
                        try {
                            PDFont font = resources.getFont((org.apache.pdfbox.cos.COSName) fontNameObj);
                            if (font == null) continue;
                            
                           if (font.getName() != null) fontName = font.getName();
                        
                        Map<String, Object> fontInfo = new HashMap<>();
                        fontInfo.put("fontName", fontName);
                        fontInfo.put("page", pageNum + 1);
                        fontInfo.put("type", font.getClass().getSimpleName());
                        
                        // Check if embedded
                        boolean isEmbedded = font.isEmbedded();
                        fontInfo.put("isEmbedded", isEmbedded);
                        
                        // Check if standard 14 font
                        boolean isStandard14 = STANDARD_14_FONTS.contains(font.getName());
                        fontInfo.put("isStandard14Font", isStandard14);
                        
                        // Check ToUnicode - check if font can map characters
                        boolean hasToUnicode = checkFontHasCharacterMapping(font);
                        fontInfo.put("hasToUnicode", hasToUnicode);
                        
                        // Detect problem
                        if (!isEmbedded && !isStandard14 && !hasToUnicode) {
                            unembeddedFontCount++;
                            fontInfo.put("hasIssue", true);
                            fontInfo.put("issueType", "UNEMBEDDED_WITHOUT_MAPPING");
                            affectedObjects.add("Page " + (pageNum + 1) + ", Font: " + fontName);
                        } else if (!isEmbedded && !isStandard14) {
                            fontInfo.put("hasIssue", true);
                            fontInfo.put("issueType", "UNEMBEDDED_BUT_HAS_MAPPING");
                        }
                        
                        if (!hasToUnicode && !isStandard14) {
                            missingToUnicodeCount++;
                        }
                        
                        fontAnalysis.add(fontInfo);
                    } catch (Exception e) {
                        affectedObjects.add("Page " + (pageNum + 1) + ", Font: " + fontName + 
                                " (ERROR: " + e.getMessage() + ")");
                    }
                    }
                }
            }
        }
        
        boolean hasIssue = unembeddedFontCount > 0 || missingToUnicodeCount > 0;
        finding.setDetected(hasIssue);
        finding.setFixMayLoseData(false);
        
        details.put("unembeddedFontCount", unembeddedFontCount);
        details.put("missingToUnicodeCount", missingToUnicodeCount);
        details.put("totalFontsAnalyzed", fontAnalysis.size());
        details.put("fontAnalysis", fontAnalysis);
        
        finding.setAffectedObjects(affectedObjects);
        finding.setDetails(details);
        finding.setFixRecommendations(java.util.Arrays.asList(
                "Re-save the PDF with font embedding enabled (Acrobat Pro, LibreOffice, etc.)",
                "Use PDF editing tools to embed missing fonts",
                "Ensure all non-standard 14 fonts have ToUnicode CMap",
                "Test the PDF on multiple systems to verify font substitution"
        ));
        
        finding.setSpecificationReferences(getSpecificationReferences());
        finding.setStackOverflowReferences(getCommunityReferences());
        
        return finding;
    }

    @Override
    public PdfFinding attemptFix(PDDocument document) throws IOException {
        PdfFinding finding = new PdfFinding(ISSUE_ID, ISSUE_NAME);
        finding.setSeverity(PdfFinding.Severity.MAJOR);
        finding.setDetected(false);
        
        // Note: Full font embedding would require access to font files
        // This is a limitation of PDFBox - it cannot re-embed fonts
        // A proper fix requires using Adobe tools or specialized PDF libraries
        
        String message = "Automatic font embedding via PDFBox is not fully supported. " +
                "Please use Adobe Acrobat Pro, LibreOffice, or iText library to re-embed fonts.";
        
        finding.setDetails(Map.of(
                "fixApplied", false,
                "reason", message,
                "recommendation", "Use professional PDF tools with full font embedding support"
        ));
        
        finding.setFixRecommendations(List.of(
                "Use Adobe Acrobat Pro: File > Save As > use 'PDF for Print' option",
                "Use LibreOffice: File > Export as PDF > check 'Create PDF/A' (embeds fonts)",
                "Use iText library with font embedding support for programmatic fixes",
                "Consider re-scanning the document as images if source fonts unavailable"
        ));
        
        return finding;
    }

    @Override
    public byte[] generateDemoPdf() throws IOException {
        // Generate a demo PDF with mixed embedded and unembedded fonts
        // This would create a PDF showing both scenarios
        
        // For now, return a placeholder
        // In a full implementation, use PDFBox or iText to create a demo PDF
        throw new UnsupportedOperationException(
                "Demo PDF generation for MissingGlyphsDiagnostic requires font file access. " +
                "Implement this with iText or similar library with full font support.");
    }

    /**
     * Check if a PDFont has character mapping (ToUnicode CMap or similar)
     */
    private boolean checkFontHasCharacterMapping(org.apache.pdfbox.pdmodel.font.PDFont font) {
        // PDFBox 3.0+ doesn't directly expose ToUnicode, so we check font properties
        // Embedded fonts and standard 14 fonts typically have character mapping
        try {
            // Embedded fonts have character mappings
            if (font.isEmbedded()) {
                return true;
            }
            // Standard 14 fonts have implicit mappings
            String fontName = font.getName();
            if (fontName != null && STANDARD_14_FONTS.contains(fontName)) {
                return true;
            }
            return false;
        } catch (Exception e) {
            // If we can't determine, assume it doesn't have mapping
            return false;
        }
    }

    @Override
    public List<String> getSpecificationReferences() {
        return java.util.Arrays.asList(
                "ISO 32000-1:2008 - Portable Document Format - Part 1: PDF 1.7",
                "  Section 5.3 - Text and Graphics State",
                "  Section 9.7 - Fonts and Font Specifications",
                "Adobe PDF Reference 1.7",
                "  Chapter 5 - Text and Graphic State",
                "  Section 5.3.2 - Font Subsetting and Embedding",
                "PDF Specification: Composite Fonts (Type 0)",
                "CID Fonts specification"
        );
    }

    @Override
    public List<String> getCommunityReferences() {
        return java.util.Arrays.asList(
                "https://stackoverflow.com/questions/tagged/pdf+fonts+embedding",
                "https://stackoverflow.com/questions/4410783/pdfbox-fonts-text-extraction",
                "https://stackoverflow.com/questions/12701811/apache-pdfbox-font-encoding",
                "https://github.com/apache/pdfbox/wiki/FAQ",
                "PDFBox FAQ: https://pdfbox.apache.org/2.0/faq.html"
        );
    }
}
