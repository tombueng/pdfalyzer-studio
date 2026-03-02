package io.pdfalyzer.diagnostics;

import io.pdfalyzer.diagnostics.model.PdfFinding;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Example usage of the PDF Diagnostics Framework.
 * 
 * This class demonstrates how to:
 * 1. Register diagnostics
 * 2. Run diagnostics on a PDF
 * 3. Interpret results
 * 4. Serialize findings to JSON
 * 5. Attempt fixes
 */
public class PdfDiagnosticsExample {
    
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: java PdfDiagnosticsExample <pdf-file>");
            System.out.println("\nExample:");
            System.out.println("  java PdfDiagnosticsExample /path/to/problematic.pdf");
            return;
        }
        
        String pdfPath = args[0];
        File pdfFile = new File(pdfPath);
        
        if (!pdfFile.exists()) {
            System.err.println("Error: File not found: " + pdfPath);
            return;
        }
        
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            System.out.println("=".repeat(80));
            System.out.println("PDF DIAGNOSTICS REPORT");
            System.out.println("=".repeat(80));
            System.out.println("File: " + pdfFile.getAbsolutePath());
            System.out.println("Size: " + (pdfFile.length() / 1024) + " KB");
            System.out.println("Pages: " + document.getNumberOfPages());
            System.out.println();
            
            // Create registry and run all diagnostics
            PdfDiagnosticsRegistry registry = new PdfDiagnosticsRegistry();
            
            System.out.println("Running diagnostics...");
            System.out.println("-".repeat(80));
            
            List<PdfFinding> findings = registry.diagnoseAll(document);
            
            // Display results
            for (PdfFinding finding : findings) {
                displayFinding(finding);
                System.out.println();
            }
            
            // Display summary
            Map<String, Object> summary = registry.getSummary(findings);
            displaySummary(summary);
            System.out.println();
            
            // Serialize to JSON
            DiagnosticsJsonSerializer serializer = new DiagnosticsJsonSerializer();
            String jsonReport = serializer.serializeDiagnosticReport(
                    pdfFile.getName(),
                    findings,
                    summary
            );
            
            System.out.println("=".repeat(80));
            System.out.println("JSON REPORT:");
            System.out.println("=".repeat(80));
            System.out.println(jsonReport);
            
            // Offer to attempt fixes
            long detectedCount = findings.stream().filter(PdfFinding::isDetected).count();
            if (detectedCount > 0) {
                System.out.println();
                System.out.println("=".repeat(80));
                System.out.println("ATTEMPTING FIXES...");
                System.out.println("=".repeat(80));
                
                List<PdfFinding> fixResults = registry.fixAll(document);
                for (PdfFinding fixResult : fixResults) {
                    System.out.println(fixResult.getIssueName() + ": " +
                            (fixResult.getDetails().containsKey("fixApplied") && 
                             (boolean) fixResult.getDetails().get("fixApplied") ? 
                             "FIXED" : "NOT FIXED"));
                }
                
                // Save fixed document
                String outputPath = pdfPath.replaceAll("\\.pdf$", "_fixed.pdf");
                document.save(outputPath);
                System.out.println("\nFixed PDF saved to: " + outputPath);
            }
            
        } catch (Exception e) {
            System.err.println("Error processing PDF: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void displayFinding(PdfFinding finding) {
        System.out.println("[" + finding.getSeverity().getDescription() + "] " + 
                finding.getIssueName());
        System.out.println("  ID: " + finding.getIssueId());
        System.out.println("  Detected: " + (finding.isDetected() ? "YES" : "NO"));
        
        if (finding.isDetected() && !finding.getAffectedObjects().isEmpty()) {
            System.out.println("  Affected Objects:");
            for (String obj : finding.getAffectedObjects()) {
                System.out.println("    - " + obj);
            }
        }
        
        if (finding.getDetails() != null && !finding.getDetails().isEmpty()) {
            System.out.println("  Details:");
            finding.getDetails().forEach((key, value) -> 
                    System.out.println("    " + key + ": " + value));
        }
        
        if (finding.getFixRecommendations() != null && !finding.getFixRecommendations().isEmpty()) {
            System.out.println("  Recommendations:");
            for (String rec : finding.getFixRecommendations()) {
                System.out.println("    - " + rec);
            }
        }
    }
    
    private static void displaySummary(Map<String, Object> summary) {
        System.out.println("=".repeat(80));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(80));
        System.out.println("Overall Status: " + summary.get("overallStatus"));
        System.out.println("Total Issues Detected: " + summary.get("totalIssuesDetected"));
        System.out.println("  Critical: " + summary.get("criticalCount"));
        System.out.println("  Major: " + summary.get("majorCount"));
        System.out.println("  Minor: " + summary.get("minorCount"));
    }
}
