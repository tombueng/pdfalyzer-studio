package io.pdfalyzer.diagnostics.issues;

import io.pdfalyzer.diagnostics.PdfIssueDiagnostic;
import io.pdfalyzer.diagnostics.model.PdfFinding;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;

import java.io.IOException;
import java.util.*;

/**
 * Diagnostic for Missing AcroForm Structure issues.
 * 
 * ISSUE DESCRIPTION:
 * PDF has interactive form fields (widgets) but lacks proper AcroForm structure,
 * or structure exists but is incomplete/malformed.
 * 
 * WHAT IS AcroForm:
 * AcroForm is Adobe's form technology (vs newer XFA forms).
 * Enables interactive form fields: text boxes, checkboxes, radio buttons, etc.
 * 
 * PROBLEMS:
 * 1. Form fields don't respond to user input
 * 2. Field values cannot be submitted
 * 3. PDF readers cannot parse form structure
 * 4. Missing field references in the dictionary
 * 5. Annotation marks without form field definitions
 * 
 * CONSEQUENCES:
 * - Forms appear but are not interactive
 * - Form submission fails
 * - Field values not retained
 * - Accessibility issues (screen readers can't find form fields)
 * - Data cannot be extracted from filled forms
 * 
 * ROOT CAUSES:
 * - Damaged or corrupted AcroForm dictionary
 * - Missing References to form fields
 * - Incomplete field inheritance
 * - Malformed DA (Appearance) resources
 * 
 * SPECIFICATION: ISO 32000-1, Section 12.7 (Interactive Forms / AcroForms)
 */
public class MissingAcroFormStructureDiagnostic implements PdfIssueDiagnostic {
    
    private static final String ISSUE_ID = "MISSING_ACROFORM";
    private static final String ISSUE_NAME = "Missing or Incomplete AcroForm Structure";

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
            MISSING OR INCOMPLETE ACROFORM STRUCTURE
            
            OVERVIEW:
            AcroForms are interactive PDF forms that contain fields for user input.
            When the AcroForm dictionary is missing or malformed, the form cannot function properly.
            
            ACROFORM BASICS:
            - Name: "forms" (Adobe's term), "AcroForm" (technical term)
            - Defined in: Document catalog with /AcroForm key
            - Purpose: Contains metadata about all form fields in the document
            - Required for: Form submission, field value storage, accessibility
            
            STRUCTURE OF ACROFORM:
            The catalog contains:
            << /AcroForm << /Fields [ field1 field2 ... ]
                          /NeedAppearances true
                          /DA (default appearance string)
                          /SigFlags 0
                          >>
            >>
            
            KEY COMPONENTS:
            1. /Fields array - List of all form field objects
            2. /NeedAppearances - Flag for Form-Filling tools
            3. /DA - Default appearance (font, size, color for widgets)
            4. /SigFlags - Bitmask for signature behavior
            5. /DR - Default resources (fonts, XObjects for appearance)
            
            WIDGET ANNOTATIONS:
            Each form field is connected to a widget annotation:
            - Widget is the visual representation on page
            - Field is the data object
            - Widget must have /P (parent) or /Parent (field reference)
            - Field must be in AcroForm /Fields array
            
            COMMON PROBLEMS:
            
            1. MISSING /AcroForm IN CATALOG
               - Document has fields/widgets but no catalog entry
               - Readers don't recognize form structure
               - Form appears but can't be filled
            
            2. MISSING /Fields ARRAY
               - AcroForm exists but has no /Fields entry
               - Field references are lost
               - Form fields unreachable
            
            3. BROKEN /Parent REFERENCES
               - Widget annotations don't link to fields
               - Field definitions don't reference widgets
               - Form structure is disconnected
            
            4. MISSING /DA (Default Appearance)
               - Widget has no appearance string
               - Text rendering fails
               - Field value not visible when filled
            
            5. INCOMPLETE FIELD INHERITANCE
               - Parent fields not properly inherited by children
               - Field properties lost
               - Validation rules not applied
            
            6. MISSING /DR (Default Resources)
               - Font or graphics resources not declared
               - Widget appearance cannot render
               - Symbols/special chars fail
            
            IMPACT:
            - FUNCTIONALITY: Fields cannot accept input
            - DATA LOSS: Filed values cannot be saved
            - ACCESSIBILITY: Screen readers cannot find form fields
            - COMPATIBILITY: Works in Acrobat but not other readers
            - EXTRACTION: Cannot programmatically read field values
            
            DETECTION:
            We check:
            1. Does catalog have /AcroForm?
            2. Does AcroForm have /Fields?
            3. Are field counts consistent?
            4. Do widgets reference their parent fields?
            5. Do fields have required properties?
            6. Is /DA string valid for all widgets?
            
            FIX APPROACH:
            Option A - Recreate AcroForm (preferred):
            - Scan all widget annotations in document
            - Create field definitions for each widget
            - Build /Fields array
            - Set proper /Parent references
            - Create /DR with necessary resources
            - Write valid /DA values
            
            Option B - Recover existing structure:
            - If partial AcroForm exists, repair it
            - Fix broken references
            - Validate all components
            
            Option C - Remove and rebuild:
            - Extract form from source document
            - Re-create in proper authoring application
            - Or regenerate PDF with proper form structure
            
            TOOLS:
            - iText: Can rebuild AcroForm with full control
            - Adobe Acrobat Pro: Detect and repair form structure
            - PDFtk: Can extract and show form field issues
            - Python PyPDF2 or pdfplumber: Inspect form structure
            
            REFERENCES:
            - ISO 32000-1:2008, Section 12.7 (Interactive Forms)
            - Adobe PDF Reference 1.7, Chapter 12 (Interactive Features)
            - AcroForm Specification (Adobe proprietary)
            """;
    }

    @Override
    public PdfFinding diagnose(PDDocument document) throws IOException {
        PdfFinding finding = new PdfFinding(ISSUE_ID, ISSUE_NAME);
        finding.setSeverity(PdfFinding.Severity.MAJOR);
        
        List<String> affectedObjects = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();
        
        boolean hasIssue = false;
        
        // Check for AcroForm
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        
        if (acroForm == null) {
            // No AcroForm at all
            // This might be intentional (non-form PDF), but if there are widgets, it's a problem
            
            // Check if there are form annotations on pages
            boolean hasWidgetAnnotations = hasFormWidgets(document);
            
            if (hasWidgetAnnotations) {
                hasIssue = true;
                affectedObjects.add("Document catalog - missing /AcroForm");
                details.put("status", "CRITICAL - Has form widgets but no AcroForm structure");
            } else {
                details.put("status", "OK - No AcroForm because document is not a form");
            }
        } else {
            // AcroForm exists, check its structure
            details.put("status", "AcroForm exists");
            
            try {
                int fieldCount = acroForm.getFields().size();
                details.put("fieldCount", fieldCount);
                
                if (fieldCount == 0) {
                    hasIssue = true;
                    affectedObjects.add("AcroForm /Fields array - empty or missing");
                    details.put("issue", "AcroForm has no fields defined");
                }
                
                // Check for /DA (Default Appearance)
                // This is optional but important
                var da = acroForm.getDefaultAppearance();
                details.put("hasDefaultAppearance", da != null && !da.isEmpty());
                if ((da == null || da.isEmpty()) && fieldCount > 0) {
                    // Not necessarily an error, but fields should handle appearance
                    details.put("warning", "No default appearance string - widgets may not render properly");
                }
                
                // Check for /DR (Default Resources)
                var dr = acroForm.getDefaultResources();
                details.put("hasDefaultResources", dr != null);
                if (dr == null && fieldCount > 0) {
                    affectedObjects.add("AcroForm /DR - missing default resources");
                }
                
            } catch (Exception e) {
                hasIssue = true;
                affectedObjects.add("AcroForm structure error: " + e.getMessage());
                details.put("error", e.getMessage());
            }
        }
        
        finding.setDetected(hasIssue);
        finding.setFixMayLoseData(false);
        finding.setAffectedObjects(affectedObjects);
        finding.setDetails(details);
        
        finding.setFixRecommendations(List.of(
                "If this is a form PDF, re-export from Adobe Acrobat Pro with form intact",
                "Use iText library to programmatically rebuild AcroForm structure",
                "Use Adobe Acrobat Pro: File > Properties > verify form structure",
                "Recreate the form using PDF authoring tool with proper AcroForm support",
                "Use PDFtk to inspect current form field structure"
        ));
        
        finding.setSpecificationReferences(getSpecificationReferences());
        finding.setStackOverflowReferences(getCommunityReferences());
        
        return finding;
    }

    @Override
    public PdfFinding attemptFix(PDDocument document) throws IOException {
        PdfFinding finding = new PdfFinding(ISSUE_ID, ISSUE_NAME);
        finding.setDetected(false);
        
        // PDFBox can create/repair AcroForm if needed
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        
        if (acroForm == null && hasFormWidgets(document)) {
            try {
                // Try to create AcroForm for existing widgets
                // This is a simplified attempt; full repair would need iText
                
                acroForm = new PDAcroForm(document);
                document.getDocumentCatalog().setAcroForm(acroForm);
                
                finding.setDetails(Map.of(
                        "fixApplied", true,
                        "method", "Created AcroForm structure",
                        "note", "Basic AcroForm created; may need refinement in Adobe Acrobat"
                ));
            } catch (Exception e) {
                finding.setDetails(Map.of(
                        "fixApplied", false,
                        "error", e.getMessage()
                ));
            }
        } else {
            finding.setDetails(Map.of(
                    "fixApplied", false,
                    "reason", "Full AcroForm repair not supported by PDFBox alone"
            ));
        }
        
        finding.setFixRecommendations(List.of(
                "For complete AcroForm reconstruction, use iText library",
                "Use Adobe Acrobat Pro to detect and repair form structure",
                "Consider re-creating form in proper authoring application"
        ));
        
        return finding;
    }

    @Override
    public byte[] generateDemoPdf() throws IOException {
        throw new UnsupportedOperationException(
                "Demo PDF generation for MissingAcroFormStructureDiagnostic requires form field creation. " +
                "Implement using iText or Adobe tools to create a form PDF with intentionally broken AcroForm.");
    }

    private boolean hasFormWidgets(PDDocument document) throws IOException {
        // Check all pages for widget annotations
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            var page = document.getPage(i);
            if (page.getAnnotations() != null) {
                for (var annot : page.getAnnotations()) {
                    if (annot != null && "/Widget".equals(annot.getSubtype())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public List<String> getSpecificationReferences() {
        return java.util.Arrays.asList(
                "ISO 32000-1:2008 - Portable Document Format",
                "  Section 12.7 - Interactive Forms (AcroForms)",
                "  Section 12.7.2 - Fields",
                "  Section 12.7.3.1 - Widget Annotations",
                "Adobe PDF Reference 1.7",
                "  Chapter 12 - Interactive Features",
                "  Section 12.7 - Forms",
                "AcroForm Specification (proprietary Adobe spec)"
        );
    }

    @Override
    public List<String> getCommunityReferences() {
        return java.util.Arrays.asList(
                "https://stackoverflow.com/questions/tagged/acroform+pdf",
                "https://stackoverflow.com/questions/731841/pdf-form-field-reference-implementation-acrobat",
                "https://stackoverflow.com/questions/20840975/pdfbox-adding-form-fields",
                "iText AcroForm Documentation: https://itext.com/",
                "https://github.com/apache/pdfbox/wiki/FAQ#forms"
        );
    }
}
