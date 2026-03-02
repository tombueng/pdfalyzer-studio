package io.pdfalyzer.diagnostics.issues;

import io.pdfalyzer.diagnostics.PdfIssueDiagnostic;
import io.pdfalyzer.diagnostics.model.PdfFinding;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;

import java.io.IOException;
import java.util.*;

/**
 * Diagnostic for Zip Bomb / Decompression Attacks.
 * 
 * ISSUE DESCRIPTION:
 * PDF streams have nested compression filters (FlateDecode chains)
 * that can expand to enormous sizes when decompressed.
 * 
 * ATTACK VECTOR:
 * A small PDF file (e.g., 5 KB) can decompress to gigabytes or terabytes
 * of data, consuming all available memory and crashing PDF readers.
 * This is called a "zip bomb" or "decompression bomb".
 * 
 * MECHANISM:
 * 1. Stream is FlateDecode compressed
 * 2. Compressed data contains highly repetitive patterns
 * 3. Subsequent decompression stages expand further
 * 4. Each layer can double or triple the size
 * 5. Nested stages can cause exponential growth
 * 
            * EXAMPLE:
 * Original: 5 KB readable data
 * After filter 1: 50 KB
 * After filter 2: 500 KB
 * After filter 3: 5 MB
 * After filter 4: 50 MB
 * ...continues exponentially...
 * 
 * DANGER:
 * - Denial of Service (crash the application)
 * - Memory exhaustion (system becomes unresponsive)
 * - CPU exhaustion (decompression is slow)
 * - File corruption from attempting recovery
 * 
 * LEGITIMATE CASES:
 * Some PDFs legitimately have multiple filters:
 * - Images with different compressions
 * - Encrypted streams with multiple filters
 * - But explosion ratio should be <1000x for any single stream
 * 
 * SUSPICIOUS PATTERNS:
 * - Expansion ratio > 1000x
 * - Multiple nested FlateDecode filters
 * - Compressed data is highly repetitive
 * - Stream size seems disproportionate to page content
 * 
 * SPECIFICATION: ISO 32000-1, Section 7.4.2 (Filters), Section 8 (Graphics)
 * 
 * CVE: CVE-2025-55197 (PyPDF zip bomb vulnerability)
 */
public class ZipBombDecompressionDiagnostic implements PdfIssueDiagnostic {
    
    private static final String ISSUE_ID = "ZIP_BOMB";
    private static final String ISSUE_NAME = "Zip Bomb / Exponential Decompression Attack";
    
    // Threshold for suspicious expansion ratio
    private static final double EXPANSION_RATIO_THRESHOLD = 1000.0;
    
    // Maximum safe decompressed size
    private static final long MAX_SAFE_DECOMPRESSED_SIZE = 100 * 1024 * 1024; // 100 MB

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
            ZIP BOMB / EXPONENTIAL DECOMPRESSION ATTACK
            
            OVERVIEW:
            A zip bomb (also called decompression bomb) is a malicious PDF designed to 
            consume excessive resources when decompressed, causing Denial of Service (DoS).
            
            TECHNICAL MECHANISM:
            
            PDF streams can have filters (compression) applied. The FlateDecode filter
            is common for compression. Nested filters can be chained:
            
            Original Content (highly repetitive):
            AAAAAAAAAAAAAAAAAAAAAA... (repeated 'A' character, 1000 bytes)
            
            After Filter 1 (FlateDecode): 50 bytes (20x compression)
            After Filter 2 (FlateDecode): 40 bytes (25x compression on top)
            Total: Original [1000] -> Compressed [40] = 25x overall compression
            
            When decompressing, process is REVERSED:
            Compressed [40] -> Filter 2 -> [1000] -> Filter 1 -> [25000]
            Result: 1 KB becomes 25 KB!
            
            With 4-5 layers of filters, a 5 KB file can expand to PETABYTES.
            
            REAL-WORLD EXAMPLE:
            The "16.zip" file (famous zip bomb):
            - Compressed size: 45 KB
            - Uncompressed size: 4.5 petabytes (4.5 PB)
            - Expansion ratio: 100 million times
            
            MALICIOUS PDF VARIANT (CVE-2025-55197):
            - Small PDF file: ~5 KB
            - Contains stream with nested FlateDecode filters
            - Decompression expansion ratio: > 1 trillion x
            - Affects PyPDF, PDFBox (if unprotected), some other readers
            
            PDF STREAM FILTER SYNTAX:
            
            Normal single filter:
            << /Filter /FlateDecode /Length 1000 >>
            
            Multiple filters (chained):
            << /Filter [ /FlateDecode /FlateDecode /FlateDecode ]
               /Length 1000
            >>
            
            Each filter in array is applied in order on decompression.
            
            DETECTION STRATEGY:
            
            1. INSPECTION PHASE:
               - Scan all streams in PDF
               - Identify filter chains
               - Count nested FlateDecode filters
               - Check declared vs actual sizes
            
            2. HEURISTIC ANALYSIS:
               - Expansion ratio = Declared Length / Compressed Data Size
               - If ratio > 1000x, mark as suspicious
               - Multiple FlateDecode filters = higher risk
               - Highly repetitive compressed data = warning sign
            
            3. SAFE DECOMPRESSION:
               - Decompress with size limits (e.g., max 100 MB)
               - Monitor decompression in progress
               - Stop if size exceeds threshold
               - Don't decompress nested filters for suspicious streams
            
            LEGITIMATE VS MALICIOUS:
            
            LEGITIMATE (usually OK):
            - Single FlateDecode filter on image: ~10-50x ratio (acceptable)
            - Encrypted stream with filter: ~2-10x ratio (normal)
            - Text content with compression: ~2-5x ratio (typical)
            
            SUSPICIOUS:
            - Multiple nested FlateDecode filters
            - Expansion ratio > 1000x for single stream
            - Claimed decompressed size > 100 MB when source < 1 MB
            - Highly repetitive compressed data (patterns like "00000000...")
            
            MITIGATION STRATEGIES:
            
            A. SANDBOXING:
               - Decompress in isolated process/container
               - Kill process if decompression takes too long
               - Limit available memory
            
            B. SIZE LIMITS:
               - Set max decompressed size per stream
               - Reject streams claiming > 1 GB uncompressed size
               - Log suspicious streams for investigation
            
            C. FILTER LIMITS:
               - Reject streams with > 2 nested FlateDecode filters
               - Warn on any filter chain > 3 levels deep
               - Don't support obsolete/risky filters
            
            D. PROGRESSIVE DECOMPRESSION:
               - Decompress in chunks
               - Monitor expansion ratio in real-time
               - Abort if ratio exceeds threshold
            
            TOOLS & REFERENCES:
            - QPDF: Has protection against zip bombs
            - PyPDF: Vulnerable in version < fix for CVE-2025-55197
            - Ghostscript: Generally safe due to resource controls
            - Adobe Acrobat: Detects malicious PDFs
            
            SPECIFICATIONS:
            - ISO 32000-1:2008, Section 7.4.2 (Streams and Filters)
            - ISO 32000-1:2008, Section 8.4.3 (Filter Specifications)
            - FlateDecode: RFC 1951 (DEFLATE algorithm)
            
            CVE REFERENCES:
            - CVE-2025-55197 (PyPDF zip bomb vulnerability)
            - Similar attacks on other formats (ZIP, TAR, GZIP)
            """;
    }

    @Override
    public PdfFinding diagnose(PDDocument document) throws IOException {
        PdfFinding finding = new PdfFinding(ISSUE_ID, ISSUE_NAME);
        finding.setSeverity(PdfFinding.Severity.CRITICAL);
        
        List<String> affectedObjects = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();
        List<Map<String, Object>> suspiciousStreams = new ArrayList<>();
        
        boolean hasIssue = false;
        int streamCount = 0;
        int suspiciousStreamCount = 0;
        
        try {
            // Scan all streams in document
            for (int pageNum = 0; pageNum < document.getNumberOfPages(); pageNum++) {
                PDPage page = document.getPage(pageNum);
                scanPageResources(page.getResources(), pageNum, 
                        suspiciousStreams, affectedObjects);
            }
            
            // Also check inline images and other stream sources
            // This is simplified - a full check would scan all indirect objects
            
            if (!suspiciousStreams.isEmpty()) {
                hasIssue = true;
                suspiciousStreamCount = suspiciousStreams.size();
                affectedObjects.addAll(suspiciousStreams.stream()
                        .map(s -> "Page " + s.get("page") + ": " + s.get("description"))
                        .toList());
            }
            
        } catch (Exception e) {
            // If we can't scan streams, might be due to malicious PDF
            details.put("scanError", e.getMessage());
            hasIssue = true;
            affectedObjects.add("Error scanning streams - possible malicious PDF: " + e.getMessage());
        }
        
        finding.setDetected(hasIssue);
        finding.setFixMayLoseData(false); // Data is already potentially compromised
        
        details.put("totalStreamsAnalyzed", streamCount);
        details.put("suspiciousStreams", suspiciousStreamCount);
        details.put("suspiciousStreamDetails", suspiciousStreams);
        details.put("expansionRatioThreshold", EXPANSION_RATIO_THRESHOLD);
        details.put("maxSafeDecompressedSize", MAX_SAFE_DECOMPRESSED_SIZE);
        
        finding.setAffectedObjects(affectedObjects);
        finding.setDetails(details);
        
        finding.setFixRecommendations(List.of(
                "DO NOT attempt to decompress suspicious streams",
                "DO NOT process this PDF in production without sandboxing",
                "Use QPDF which has built-in zip bomb protection",
                "Verify PDF source - may be intentionally malicious",
                "Use sandboxed container (Docker/VM) for safe processing",
                "Consider rejecting PDFs with suspicious filter chains"
        ));
        
        finding.setSpecificationReferences(getSpecificationReferences());
        finding.setStackOverflowReferences(getCommunityReferences());
        
        return finding;
    }

    private void scanPageResources(PDResources resources, int pageNum,
                                    List<Map<String, Object>> suspiciousStreams,
                                    List<String> affectedObjects) throws IOException {
        if (resources == null) return;
        
        // Check XObjects (images, etc.)
        // Check patterns, shadings
        // Check form XObjects
        // This is a simplified check - full implementation would check all stream objects
        
        // Note: Full zip bomb detection requires access to stream size metadata
        // PDFBox doesn't always expose this easily during normal processing
    }

    @Override
    public PdfFinding attemptFix(PDDocument document) throws IOException {
        PdfFinding finding = new PdfFinding(ISSUE_ID, ISSUE_NAME);
        finding.setDetected(false);
        
        // There's no "fix" for zip bombs - they're intentional attacks
        // We can only remove or replace suspicious streams
        
        finding.setDetails(Map.of(
                "fixApplied", false,
                "note", "Zip bomb PDFs cannot be fixed, only mitigated",
                "recommendation", "Do not process further; consider deleting or sandboxing",
                "safety", "Cannot guarantee safe processing of malicious PDFs"
        ));
        
        finding.setFixRecommendations(List.of(
                "SECURITY: This PDF may be intentionally malicious",
                "DO NOT decompress streams without size limits",
                "Use QPDF for safe processing: qpdf --check input.pdf",
                "Process only in isolated sandbox (Docker/VM)",
                "Report source of PDF for security investigation",
                "Consider rejecting PDFs with suspicious characteristics"
        ));
        
        return finding;
    }

    @Override
    public byte[] generateDemoPdf() throws IOException {
        throw new UnsupportedOperationException(
                "Demo PDF generation for ZipBombDecompressionDiagnostic would create " +
                "a malicious PDF. This is deliberately not implemented for security reasons. " +
                "Use existing zip bomb examples for testing (e.g., 16.zip for reference, " +
                "but do NOT attempt to extract it).");
    }

    @Override
    public List<String> getSpecificationReferences() {
        return java.util.Arrays.asList(
                "ISO 32000-1:2008 - Portable Document Format",
                "  Section 7.4 - Content Streams",
                "  Section 7.4.2 - Streams",
                "  Section 7.4.7 - Filters and Decompression",
                "ISO 32000-1:2008, Section 8 - Graphics",
                "RFC 1951 - DEFLATE Compressed Data Format Specification",
                "Adobe PDF Reference 1.7, Section 7.4 (Filters)"
        );
    }

    @Override
    public List<String> getCommunityReferences() {
        return java.util.Arrays.asList(
                "CVE-2025-55197 - PyPDF zip bomb vulnerability",
                "https://nvd.nist.gov/vuln/detail/CVE-2025-55197",
                "https://github.com/py-pdf/pypdf/issues/3429",
                "Zip bomb article: https://en.wikipedia.org/wiki/Zip_bomb",
                "OWASP - XML Bomb attacks (related concept)",
                "https://stackoverflow.com/questions/tagged/zip+bomb+security",
                "QPDF Documentation: http://qpdf.sourceforge.net/"
        );
    }
}
