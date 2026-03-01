package io.pdfalyzer.web;

import io.pdfalyzer.model.*;
import io.pdfalyzer.service.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core API: upload, tree, fonts, validation.
 * Resource/attachment endpoints live in {@link ResourceApiController}.
 * Edit endpoints live in {@link EditApiController}.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final PdfService pdfService;
    private final FontInspectorService fontInspectorService;
    private final ValidationService validationService;
    private final PdfStructureParser structureParser;
    private final VeraPdfService veraPdfService;

    public ApiController(PdfService pdfService,
                         FontInspectorService fontInspectorService,
                         ValidationService validationService,
                         PdfStructureParser structureParser,
                         VeraPdfService veraPdfService) {
        this.pdfService = pdfService;
        this.fontInspectorService = fontInspectorService;
        this.validationService = validationService;
        this.structureParser = structureParser;
        this.veraPdfService = veraPdfService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        String filename = file.getOriginalFilename();
        if (file.isEmpty()) throw new IllegalArgumentException("Uploaded file is empty");
        if (filename == null || !filename.toLowerCase().endsWith(".pdf"))
            throw new IllegalArgumentException("Only PDF files are accepted");
        PdfSession session = pdfService.uploadAndParse(filename, file.getBytes());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("filename", session.getFilename());
        result.put("pageCount", session.getPageCount());
        result.put("tree", session.getTreeRoot());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/tree/{sessionId}")
    public ResponseEntity<PdfNode> getTree(@PathVariable("sessionId") String sessionId) {
        return ResponseEntity.ok(pdfService.getTree(sessionId));
    }

    @GetMapping("/tree/{sessionId}/search")
    public ResponseEntity<List<PdfNode>> searchTree(@PathVariable("sessionId") String sessionId,
                                                     @RequestParam("q") String q) {
        return ResponseEntity.ok(pdfService.searchTree(sessionId, q));
    }

    @GetMapping("/pdf/{sessionId}")
    public ResponseEntity<byte[]> getPdfBytes(@PathVariable("sessionId") String sessionId) {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length).body(bytes);
    }

    @GetMapping("/pdf/{sessionId}/download")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable("sessionId") String sessionId) {
        PdfSession session = pdfService.getSession(sessionId);
        byte[] bytes = session.getPdfBytes();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + session.getFilename() + "\"")
                .contentLength(bytes.length).body(bytes);
    }

    @GetMapping("/fonts/{sessionId}")
    public ResponseEntity<List<FontInfo>> getFonts(@PathVariable("sessionId") String sessionId) throws IOException {
        return ResponseEntity.ok(fontInspectorService.analyzeFonts(
                pdfService.getSessionPdfBytes(sessionId)));
    }

    @GetMapping("/fonts/{sessionId}/page/{pageNum}")
    public ResponseEntity<List<FontInfo>> getPageFonts(@PathVariable("sessionId") String sessionId,
                                                        @PathVariable("pageNum") int pageNum) throws IOException {
        return ResponseEntity.ok(fontInspectorService.analyzePageFonts(
                pdfService.getSessionPdfBytes(sessionId), pageNum));
    }

    @GetMapping("/fonts/{sessionId}/charmap/{pageNum}/{fontObjectId}")
    public ResponseEntity<Map<String, String>> getCharMap(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("pageNum") int pageNum,
            @PathVariable("fontObjectId") String fontObjectId) throws IOException {
        return ResponseEntity.ok(fontInspectorService.getCharacterMap(
                pdfService.getSessionPdfBytes(sessionId), pageNum, fontObjectId));
    }

    @GetMapping("/fonts/{sessionId}/usage/{objNum}/{genNum}")
    public ResponseEntity<List<Map<String, Object>>> getFontUsage(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("objNum") int objNum,
            @PathVariable("genNum") int genNum) throws IOException {
        return ResponseEntity.ok(fontInspectorService.getFontUsageAreas(
                pdfService.getSessionPdfBytes(sessionId), objNum, genNum));
    }

    @GetMapping("/fonts/{sessionId}/extract/{objNum}/{genNum}")
    public ResponseEntity<byte[]> extractFont(@PathVariable("sessionId") String sessionId,
                                               @PathVariable("objNum") int objNum,
                                               @PathVariable("genNum") int genNum) throws IOException {
        byte[] data = fontInspectorService.extractFontFile(
                pdfService.getSessionPdfBytes(sessionId), objNum, genNum);
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"font-" + objNum + "-" + genNum + ".bin\"")
                .contentLength(data.length).body(data);
    }

    @GetMapping("/validate/{sessionId}")
    public ResponseEntity<List<ValidationIssue>> validate(@PathVariable("sessionId") String sessionId)
            throws IOException {
        return ResponseEntity.ok(validationService.validate(
                pdfService.getSessionPdfBytes(sessionId)));
    }

    @GetMapping("/validate/{sessionId}/verapdf")
    public ResponseEntity<Map<String, Object>> validateWithVeraPdf(@PathVariable("sessionId") String sessionId)
            throws IOException {
        return ResponseEntity.ok(veraPdfService.validate(pdfService.getSessionPdfBytes(sessionId)));
    }

    @GetMapping("/validate/{sessionId}/export")
    public ResponseEntity<byte[]> exportValidationReport(@PathVariable("sessionId") String sessionId)
            throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        List<ValidationIssue> issues = validationService.validate(bytes);
        PdfSession session = pdfService.getSession(sessionId);
        String report = buildValidationReport(session, issues);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"validation-report.txt\"")
                .body(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/tree/{sessionId}/export")
    public ResponseEntity<PdfNode> exportTree(@PathVariable("sessionId") String sessionId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tree-export.json\"")
                .body(pdfService.getTree(sessionId));
    }

    @GetMapping("/tree/{sessionId}/raw-cos")
    public ResponseEntity<PdfNode> getRawCosTree(@PathVariable("sessionId") String sessionId) throws IOException {
        PdfSession session = pdfService.getSession(sessionId);
        PdfNode rawCos = session.getRawCosTree();
        if (rawCos == null) {
            try (PDDocument doc = Loader.loadPDF(session.getPdfBytes())) {
                rawCos = structureParser.buildRawCosTree(doc);
                session.setRawCosTree(rawCos);
            }
        }
        return ResponseEntity.ok(rawCos);
    }

    // ======================== PRIVATE ========================

    private String buildValidationReport(PdfSession session, List<ValidationIssue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("PDFalyzer Validation Report\n==========================\n");
        sb.append("File: ").append(session.getFilename()).append("\n");
        sb.append("Pages: ").append(session.getPageCount()).append("\n");
        sb.append("Issues found: ").append(issues.size()).append("\n\n");
        int errors = 0, warnings = 0, infos = 0;
        for (ValidationIssue issue : issues) {
            if ("ERROR".equals(issue.getSeverity())) errors++;
            else if ("WARNING".equals(issue.getSeverity())) warnings++;
            else infos++;
        }
        sb.append("Summary: ").append(errors).append(" errors, ")
                .append(warnings).append(" warnings, ").append(infos).append(" info\n\n");
        for (ValidationIssue issue : issues) {
            sb.append("[").append(issue.getSeverity()).append("] ")
                    .append(issue.getRuleId()).append(": ")
                    .append(issue.getMessage()).append("\n");
            sb.append("  Location: ").append(issue.getLocation()).append("\n");
            sb.append("  Reference: ").append(issue.getSpecReference()).append("\n\n");
        }
        return sb.toString();
    }
}
