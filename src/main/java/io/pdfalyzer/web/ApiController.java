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

@RestController
@RequestMapping("/api")
public class ApiController {

    private final PdfService pdfService;
    private final FontInspectorService fontInspectorService;
    private final ValidationService validationService;
    private final PdfEditService pdfEditService;
    private final CosEditService cosEditService;
    private final PdfStructureParser structureParser;

    public ApiController(PdfService pdfService,
                         FontInspectorService fontInspectorService,
                         ValidationService validationService,
                         PdfEditService pdfEditService,
                         CosEditService cosEditService,
                         PdfStructureParser structureParser) {
        this.pdfService = pdfService;
        this.fontInspectorService = fontInspectorService;
        this.validationService = validationService;
        this.pdfEditService = pdfEditService;
        this.cosEditService = cosEditService;
        this.structureParser = structureParser;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are accepted");
        }

        PdfSession session = pdfService.uploadAndParse(filename, file.getBytes());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("filename", session.getFilename());
        result.put("pageCount", session.getPageCount());
        result.put("tree", session.getTreeRoot());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/tree/{sessionId}")
    public ResponseEntity<PdfNode> getTree(@PathVariable String sessionId) {
        return ResponseEntity.ok(pdfService.getTree(sessionId));
    }

    @GetMapping("/tree/{sessionId}/search")
    public ResponseEntity<List<PdfNode>> searchTree(@PathVariable String sessionId,
                                                     @RequestParam String q) {
        return ResponseEntity.ok(pdfService.searchTree(sessionId, q));
    }

    @GetMapping("/pdf/{sessionId}")
    public ResponseEntity<byte[]> getPdfBytes(@PathVariable String sessionId) {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .body(bytes);
    }

    @GetMapping("/pdf/{sessionId}/download")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable String sessionId) {
        PdfSession session = pdfService.getSession(sessionId);
        byte[] bytes = session.getPdfBytes();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + session.getFilename() + "\"")
                .contentLength(bytes.length)
                .body(bytes);
    }

    @GetMapping("/fonts/{sessionId}")
    public ResponseEntity<List<FontInfo>> getFonts(@PathVariable String sessionId) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        return ResponseEntity.ok(fontInspectorService.analyzeFonts(bytes));
    }

    @GetMapping("/fonts/{sessionId}/page/{pageNum}")
    public ResponseEntity<List<FontInfo>> getPageFonts(@PathVariable String sessionId,
                                                        @PathVariable int pageNum) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        return ResponseEntity.ok(fontInspectorService.analyzePageFonts(bytes, pageNum));
    }

    @GetMapping("/validate/{sessionId}")
    public ResponseEntity<List<ValidationIssue>> validate(@PathVariable String sessionId) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        return ResponseEntity.ok(validationService.validate(bytes));
    }

    @GetMapping("/validate/{sessionId}/export")
    public ResponseEntity<byte[]> exportValidationReport(@PathVariable String sessionId) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        List<ValidationIssue> issues = validationService.validate(bytes);
        PdfSession session = pdfService.getSession(sessionId);

        StringBuilder sb = new StringBuilder();
        sb.append("PDFalyzer Validation Report\n");
        sb.append("==========================\n");
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

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"validation-report.txt\"")
                .body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/tree/{sessionId}/export")
    public ResponseEntity<PdfNode> exportTree(@PathVariable String sessionId) {
        PdfNode tree = pdfService.getTree(sessionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"tree-export.json\"")
                .body(tree);
    }

    @GetMapping("/tree/{sessionId}/raw-cos")
    public ResponseEntity<PdfNode> getRawCosTree(@PathVariable String sessionId) throws IOException {
        PdfSession session = pdfService.getSession(sessionId);
        PdfNode rawCos = session.getRawCosTree();
        if (rawCos == null) {
            // Build on-demand (lazy) and cache in session
            try (PDDocument doc = Loader.loadPDF(session.getPdfBytes())) {
                rawCos = structureParser.buildRawCosTree(doc);
                session.setRawCosTree(rawCos);
            }
        }
        return ResponseEntity.ok(rawCos);
    }

    @PostMapping("/cos/{sessionId}/update")
    public ResponseEntity<Map<String, Object>> updateCosValue(
            @PathVariable String sessionId,
            @RequestBody CosUpdateRequest request) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = cosEditService.updateCosValue(bytes, request);
        pdfService.updateSessionPdf(sessionId, modified);

        PdfSession session = pdfService.getSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("pageCount", session.getPageCount());
        result.put("tree", session.getTreeRoot());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/edit/{sessionId}/add-field")
    public ResponseEntity<Map<String, Object>> addFormField(@PathVariable String sessionId,
                                                             @RequestBody FormFieldRequest request) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = pdfEditService.addFormField(bytes, request);
        pdfService.updateSessionPdf(sessionId, modified);

        PdfSession session = pdfService.getSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("pageCount", session.getPageCount());
        result.put("tree", session.getTreeRoot());
        return ResponseEntity.ok(result);
    }
}
