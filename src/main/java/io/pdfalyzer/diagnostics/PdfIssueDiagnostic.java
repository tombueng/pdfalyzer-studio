package io.pdfalyzer.diagnostics;

import io.pdfalyzer.diagnostics.model.PdfFinding;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.List;

/**
 * Interface for PDF issue diagnostics.
 * Each implementation diagnoses a specific type of PDF issue.
 */
public interface PdfIssueDiagnostic {
    
    /**
     * Get the unique identifier for this issue type.
     * @return issue ID (e.g., "MISSING_GLYPHS")
     */
    String getIssueId();
    
    /**
     * Get the human-readable name for this issue.
     * @return issue name
     */
    String getIssueName();
    
    /**
     * Get a detailed description of the issue.
     * @return long descriptive text
     */
    String getDetailedDescription();
    
    /**
     * Diagnose if this issue is present in the PDF document.
     * @param document the PDF document to diagnose
     * @return PdfFinding with detection results
     */
    PdfFinding diagnose(PDDocument document) throws IOException;
    
    /**
     * Attempt to fix the issue in the PDF document.
     * May or may not be successful - check the returned PdfFinding.
     * @param document the PDF document to fix
     * @return PdfFinding indicating if fix was successful and whether data may be lost
     */
    PdfFinding attemptFix(PDDocument document) throws IOException;
    
    /**
     * Generate a demo PDF that exhibits this specific issue.
     * @return byte array of demo PDF
     */
    byte[] generateDemoPdf() throws IOException;
    
    /**
     * Get specification references for this issue.
     * Returns links to ISO 32000 or other relevant specs.
     * @return list of reference strings
     */
    List<String> getSpecificationReferences();
    
    /**
     * Get StackOverflow or other community references.
     * @return list of reference strings
     */
    List<String> getCommunityReferences();
}
