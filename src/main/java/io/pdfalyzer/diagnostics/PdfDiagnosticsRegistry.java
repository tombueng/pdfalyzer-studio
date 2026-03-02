package io.pdfalyzer.diagnostics;

import io.pdfalyzer.diagnostics.issues.*;
import io.pdfalyzer.diagnostics.model.PdfFinding;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.*;

/**
 * Registry and manager for all PDF issue diagnostics.
 * Coordinates diagnosis of all known issues in a single pass.
 */
public class PdfDiagnosticsRegistry {
    
    private final List<PdfIssueDiagnostic> diagnostics = new ArrayList<>();
    
    public PdfDiagnosticsRegistry() {
        // Register all available diagnostics
        registerDiagnostic(new MissingGlyphsDiagnostic());
        registerDiagnostic(new BrokenXrefDiagnostic());
        registerDiagnostic(new CorruptedTruncatedDiagnostic());
        registerDiagnostic(new MissingAcroFormStructureDiagnostic());
        registerDiagnostic(new ZipBombDecompressionDiagnostic());
    }
    
    /**
     * Register a new diagnostic
     */
    public void registerDiagnostic(PdfIssueDiagnostic diagnostic) {
        if (diagnostic != null) {
            diagnostics.add(diagnostic);
        }
    }
    
    /**
     * Get all registered diagnostics
     */
    public List<PdfIssueDiagnostic> getAllDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }
    
    /**
     * Get diagnostic by issue ID
     */
    public Optional<PdfIssueDiagnostic> getDiagnosticById(String issueId) {
        return diagnostics.stream()
                .filter(d -> d.getIssueId().equals(issueId))
                .findFirst();
    }
    
    /**
     * Diagnose all issues in a PDF document.
     * @param document the PDF document to diagnose
     * @return list of findings, one per diagnostic
     */
    public List<PdfFinding> diagnoseAll(PDDocument document) throws IOException {
        List<PdfFinding> findings = new ArrayList<>();
        for (PdfIssueDiagnostic diagnostic : diagnostics) {
            try {
                PdfFinding finding = diagnostic.diagnose(document);
                findings.add(finding);
            } catch (Exception e) {
                // Create error finding if diagnosis fails
                PdfFinding errorFinding = new PdfFinding(
                        diagnostic.getIssueId(),
                        diagnostic.getIssueName()
                );
                errorFinding.setDetails(Map.of(
                        "error", "Diagnostic failed: " + e.getMessage(),
                        "exception", e.getClass().getName()
                ));
                errorFinding.setSeverity(PdfFinding.Severity.MINOR);
                findings.add(errorFinding);
            }
        }
        return findings;
    }
    
    /**
     * Attempt to fix all detected issues.
     * Note: Fixes are applied in sequence; some may affect others.
     * @param document the PDF document to fix
     * @return list of fix results
     */
    public List<PdfFinding> fixAll(PDDocument document) throws IOException {
        List<PdfFinding> fixResults = new ArrayList<>();
        
        // First diagnose all issues
        List<PdfFinding> findings = diagnoseAll(document);
        
        // Then attempt fixes on detected issues
        for (PdfFinding finding : findings) {
            if (finding.isDetected()) {
                Optional<PdfIssueDiagnostic> diagnostic = 
                        getDiagnosticById(finding.getIssueId());
                
                if (diagnostic.isPresent()) {
                    try {
                        PdfFinding fixResult = diagnostic.get().attemptFix(document);
                        fixResults.add(fixResult);
                    } catch (Exception e) {
                        PdfFinding errorResult = new PdfFinding(
                                finding.getIssueId(),
                                finding.getIssueName()
                        );
                        errorResult.setDetails(Map.of(
                                "fixError", "Fix attempt failed: " + e.getMessage()
                        ));
                        fixResults.add(errorResult);
                    }
                }
            }
        }
        
        return fixResults;
    }
    
    /**
     * Get summary of diagnostic results
     */
    public Map<String, Object> getSummary(List<PdfFinding> findings) {
        long detectedCount = findings.stream().filter(PdfFinding::isDetected).count();
        long criticalCount = findings.stream()
                .filter(f -> f.getSeverity() == PdfFinding.Severity.CRITICAL)
                .count();
        long majorCount = findings.stream()
                .filter(f -> f.getSeverity() == PdfFinding.Severity.MAJOR)
                .count();
        
        List<Map<String, String>> issuesByName = findings.stream()
                .filter(PdfFinding::isDetected)
                .map(f -> Map.of(
                        "id", f.getIssueId(),
                        "name", f.getIssueName(),
                        "severity", f.getSeverity().getDescription()
                ))
                .toList();
        
        return Map.of(
                "totalIssuesDetected", detectedCount,
                "criticalCount", criticalCount,
                "majorCount", majorCount,
                "minorCount", findings.size() - criticalCount - majorCount - detectedCount,
                "overallStatus", detectedCount > 0 ? "NEEDS REVIEW" : "HEALTHY",
                "issues", issuesByName
        );
    }
}
