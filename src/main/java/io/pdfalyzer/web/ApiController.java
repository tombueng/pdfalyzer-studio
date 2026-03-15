package io.pdfalyzer.web;

import io.pdfalyzer.model.*;
import io.pdfalyzer.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Core API: upload, tree, fonts, validation.
 * Resource/attachment endpoints live in {@link ResourceApiController}.
 * Edit endpoints live in {@link EditApiController}.
 */
@RestController
@Slf4j
@RequestMapping("/api")
public class ApiController {
    private static final Logger clientErrorLog = LoggerFactory.getLogger("io.pdfalyzer.client-errors");

    private final PdfService pdfService;
    private final FontInspectorService fontInspectorService;
    private final ValidationService validationService;
    private final PdfStructureParser structureParser;
    private final VeraPdfService veraPdfService;
    private final FieldSchemaService fieldSchemaService;
    private final SignatureAnalysisService signatureAnalysisService;
    private final DssValidationService dssValidationService;
    private final CertificateChainBuilder certificateChainBuilder;

    public ApiController(PdfService pdfService,
                         FontInspectorService fontInspectorService,
                         ValidationService validationService,
                         PdfStructureParser structureParser,
                         VeraPdfService veraPdfService,
                         FieldSchemaService fieldSchemaService,
                         SignatureAnalysisService signatureAnalysisService,
                         DssValidationService dssValidationService,
                         CertificateChainBuilder certificateChainBuilder) {
        this.pdfService = pdfService;
        this.fontInspectorService = fontInspectorService;
        this.validationService = validationService;
        this.structureParser = structureParser;
        this.veraPdfService = veraPdfService;
        this.fieldSchemaService = fieldSchemaService;
        this.signatureAnalysisService = signatureAnalysisService;
        this.dssValidationService = dssValidationService;
        this.certificateChainBuilder = certificateChainBuilder;
    }

    @GetMapping("/fields/schema")
    public ResponseEntity<List<io.pdfalyzer.model.FieldPropertyDescriptor>> getFieldSchema() {
        return ResponseEntity.ok(fieldSchemaService.getSchema());
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
        result.put("encryptionInfo", session.getEncryptionInfo());
        result.put("hasSignatures", session.isHasSignatureFields());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/session/{sessionId}/unlock")
    public ResponseEntity<Map<String, Object>> unlockSession(
            @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> body) throws IOException {
        String password = body == null ? "" : body.getOrDefault("password", "");
        PdfSession session = pdfService.unlockSession(sessionId, password);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("filename", session.getFilename());
        result.put("pageCount", session.getPageCount());
        result.put("tree", session.getTreeRoot());
        result.put("encryptionInfo", session.getEncryptionInfo());
        result.put("hasSignatures", session.isHasSignatureFields());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/sample/latest")
    public ResponseEntity<byte[]> getLatestSamplePdf() throws IOException {
        byte[] data = loadSamplePdfBytes();

        if (data == null || data.length == 0) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"test.pdf\"")
                .contentLength(data.length)
                .body(data);
    }

    @GetMapping("/sample/list")
    public ResponseEntity<List<String>> listSamples() throws IOException {
        List<String> names;
        Path dir = Paths.get("src", "main", "resources", "sample-pdfs").toAbsolutePath().normalize();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                names = stream
                        .filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .collect(Collectors.toList());
            }
        } else {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:/sample-pdfs/*.pdf");
            names = new ArrayList<>();
            for (Resource r : resources) {
                if (r.getFilename() != null) names.add(r.getFilename());
            }
            Collections.sort(names);
        }
        return ResponseEntity.ok(names);
    }

    @GetMapping("/sample/load")
    public ResponseEntity<Map<String, Object>> loadLatestSamplePdfSessionGet() throws IOException {
        return loadLatestSamplePdfSession();
    }

    @PostMapping("/sample/load")
    public ResponseEntity<Map<String, Object>> loadLatestSamplePdfSession() throws IOException {
        return loadSampleByName("test.pdf");
    }

    @PostMapping("/sample/load/{filename}")
    public ResponseEntity<Map<String, Object>> loadSampleByName(
            @PathVariable("filename") String filename) throws IOException {
        byte[] data = loadSamplePdfBytes(filename);
        if (data == null || data.length == 0) {
            return ResponseEntity.notFound().build();
        }
        PdfSession session = pdfService.uploadAndParse(filename, data);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("filename", session.getFilename());
        result.put("pageCount", session.getPageCount());
        result.put("tree", session.getTreeRoot());
        result.put("fileSize", data.length);
        result.put("encryptionInfo", session.getEncryptionInfo());
        result.put("hasSignatures", session.isHasSignatureFields());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/session/{sessionId}/restore")
    public ResponseEntity<Map<String, Object>> restoreSession(@PathVariable("sessionId") String sessionId) {
        PdfSession session = pdfService.getSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("filename", session.getFilename());
        result.put("pageCount", session.getPageCount());
        result.put("fileSize", session.getPdfBytes() == null ? 0 : session.getPdfBytes().length);
        result.put("tree", session.getTreeRoot());
        result.put("hasSignatures", session.isHasSignatureFields());
        return ResponseEntity.ok(result);
    }

    private byte[] loadSamplePdfBytes() throws IOException {
        return loadSamplePdfBytes("test.pdf");
    }

    private byte[] loadSamplePdfBytes(String filename) throws IOException {
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")
                || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return null;
        }
        Path samplesDir = Paths.get("src", "main", "resources", "sample-pdfs").toAbsolutePath().normalize();
        Path sourceFile = samplesDir.resolve(filename).normalize();
        if (!sourceFile.startsWith(samplesDir)) return null;

        if (Files.exists(sourceFile) && Files.isRegularFile(sourceFile)) {
            return Files.readAllBytes(sourceFile);
        }

        try (InputStream in = getClass().getResourceAsStream("/sample-pdfs/" + filename)) {
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                return out.toByteArray();
            }
        }
        return null;
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

    /**
     * Downloads the PDF with optional re-encryption.  The request body controls
     * whether to keep the original protection, apply custom passwords/algorithm,
     * or export without any protection.
     */
    @PostMapping("/pdf/{sessionId}/download")
    public ResponseEntity<byte[]> downloadPdfProtected(
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) DownloadProtectionRequest req) throws IOException {
        PdfSession session = pdfService.getSession(sessionId);
        byte[] bytes = pdfService.downloadProtected(sessionId, req);
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

        @GetMapping("/fonts/{sessionId}/usage/{objNum}/{genNum}/glyph/{code}")
        public ResponseEntity<List<Map<String, Object>>> getGlyphUsage(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("objNum") int objNum,
            @PathVariable("genNum") int genNum,
            @PathVariable("code") int code) throws IOException {
        return ResponseEntity.ok(fontInspectorService.getGlyphUsageAreas(
            pdfService.getSessionPdfBytes(sessionId), objNum, genNum, code));
        }

        @GetMapping("/fonts/{sessionId}/diagnostics")
        public ResponseEntity<FontDiagnostics> getFontDiagnostics(
            @PathVariable("sessionId") String sessionId) throws IOException {
        return ResponseEntity.ok(fontInspectorService.analyzeFontIssues(
            pdfService.getSessionPdfBytes(sessionId)));
        }

        @GetMapping("/fonts/{sessionId}/diagnostics/{objNum}/{genNum}")
        public ResponseEntity<FontDiagnostics.FontDiagnosticsDetail> getFontDiagnosticsDetail(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("objNum") int objNum,
            @PathVariable("genNum") int genNum) throws IOException {
        return ResponseEntity.ok(fontInspectorService.analyzeFontIssueDetail(
            pdfService.getSessionPdfBytes(sessionId), objNum, genNum));
        }

        @GetMapping("/fonts/{sessionId}/diagnostics/{objNum}/{genNum}/glyph/{code}")
        public ResponseEntity<Map<String, Object>> getFontGlyphDiagnosticsDetail(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("objNum") int objNum,
            @PathVariable("genNum") int genNum,
            @PathVariable("code") int code) throws IOException {
        return ResponseEntity.ok(fontInspectorService.analyzeGlyphDetail(
            pdfService.getSessionPdfBytes(sessionId), objNum, genNum, code));
        }

    @GetMapping("/fonts/{sessionId}/extract/{objNum}/{genNum}")
    public ResponseEntity<byte[]> extractFont(@PathVariable("sessionId") String sessionId,
                                               @PathVariable("objNum") int objNum,
                                               @PathVariable("genNum") int genNum) throws IOException {
        FontInspectorService.FontFileDownload download = fontInspectorService.extractFontFileDownload(
                pdfService.getSessionPdfBytes(sessionId), objNum, genNum);
        if (download == null) return ResponseEntity.notFound().build();
        String contentDisposition = ContentDisposition.attachment()
            .filename(download.getFilename(), StandardCharsets.UTF_8)
            .build()
            .toString();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                contentDisposition)
            .contentLength(download.getData().length).body(download.getData());
    }

    @GetMapping("/signatures/{sessionId}")
    public ResponseEntity<SignatureAnalysisResult> getSignatures(
            @PathVariable("sessionId") String sessionId) throws IOException {
        return ResponseEntity.ok(signatureAnalysisService.analyzeSignatures(
                pdfService.getSessionPdfBytes(sessionId)));
    }

    @GetMapping("/signatures/{sessionId}/{fieldName}/chain.pem")
    public ResponseEntity<byte[]> downloadCertificateChainPem(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("fieldName") String fieldName,
            @RequestParam(value = "type", defaultValue = "signer") String chainType) throws IOException {
        byte[] pdfBytes = pdfService.getSessionPdfBytes(sessionId);

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                return ResponseEntity.notFound().build();
            }

            PDSignatureField sigField = null;
            for (PDField field : acroForm.getFieldTree()) {
                if (field instanceof PDSignatureField sf &&
                        fieldName.equals(sf.getPartialName())) {
                    sigField = sf;
                    break;
                }
            }
            if (sigField == null || sigField.getSignature() == null) {
                return ResponseEntity.notFound().build();
            }

            PDSignature sig = sigField.getSignature();
            byte[] contents = sig.getContents(pdfBytes);
            if (contents == null || contents.length == 0) {
                return ResponseEntity.notFound().build();
            }

            CMSSignedData cmsData = new CMSSignedData(contents);
            SignerInformation signer = cmsData.getSignerInfos().getSigners().iterator().next();

            List<java.security.cert.X509Certificate> rawChain;
            String filenamePrefix;

            if ("tsa".equals(chainType)) {
                CertificateChainBuilder.TsaChainResult tsaResult =
                        certificateChainBuilder.extractTsaChain(cmsData, signer);
                if (!tsaResult.hasTsa()) {
                    return ResponseEntity.notFound().build();
                }
                rawChain = tsaResult.rawCerts();
                filenamePrefix = fieldName + "-tsa-chain";
            } else {
                rawChain = certificateChainBuilder.extractRawChainFromCms(cmsData, signer);
                filenamePrefix = fieldName + "-chain";
            }

            if (rawChain.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            String pem = certificateChainBuilder.formatChainAsPem(rawChain);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-pem-file"));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(filenamePrefix + ".pem").build());
            return ResponseEntity.ok().headers(headers).body(pem.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.debug("Error exporting certificate chain PEM for field {}: {}", fieldName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/validate/{sessionId}")
    public ResponseEntity<List<ValidationIssue>> validate(@PathVariable("sessionId") String sessionId)
            throws IOException {
        return ResponseEntity.ok(validationService.validate(
                pdfService.getSessionPdfBytes(sessionId)));
    }

    @PostMapping("/repair/acroform/{sessionId}")
    public ResponseEntity<Map<String, Object>> repairAcroForm(@PathVariable String sessionId)
            throws IOException {
        byte[] pdfBytes = pdfService.getSessionPdfBytes(sessionId);
        int adopted = 0;
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                acroForm = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(acroForm);
            }

            COSArray fieldsArr = (COSArray) acroForm.getCOSObject().getDictionaryObject(COSName.FIELDS);
            if (fieldsArr == null) {
                fieldsArr = new COSArray();
                acroForm.getCOSObject().setItem(COSName.FIELDS, fieldsArr);
            }

            java.util.Set<COSDictionary> known = new java.util.HashSet<>();
            for (var field : acroForm.getFields()) known.add(field.getCOSObject());

            for (var page : doc.getPages()) {
                for (var annot : page.getAnnotations()) {
                    if (!"Widget".equals(annot.getSubtype())) continue;
                    COSDictionary dict = annot.getCOSObject();
                    if (known.contains(dict)) continue;
                    if (!dict.containsKey(COSName.T)) continue;
                    fieldsArr.add(dict);
                    known.add(dict);
                    adopted++;
                }
            }

            if (adopted > 0) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                doc.save(baos);
                pdfService.updateSessionPdf(sessionId, baos.toByteArray());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adopted", adopted);
        result.put("success", adopted > 0);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/validate/{sessionId}/verapdf")
    public ResponseEntity<Map<String, Object>> validateWithVeraPdf(@PathVariable("sessionId") String sessionId)
            throws IOException {
        return ResponseEntity.ok(veraPdfService.validate(pdfService.getSessionPdfBytes(sessionId)));
    }

    @GetMapping("/validate/{sessionId}/dss")
    public ResponseEntity<Map<String, Object>> validateWithDss(@PathVariable("sessionId") String sessionId)
            throws IOException {
        return ResponseEntity.ok(dssValidationService.validate(pdfService.getSessionPdfBytes(sessionId)));
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

    @PostMapping("/client-errors")
    public ResponseEntity<Map<String, Object>> logClientError(@RequestBody(required = false) Map<String, Object> payload,
                                                               HttpServletRequest request) {
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        String kind = sanitizeLogField(safePayload.getOrDefault("kind", "error"), 1000);
        String message = sanitizeLogField(safePayload.getOrDefault("message", "(no message)"), 4000);
        String source = sanitizeLogField(safePayload.getOrDefault("source", ""), 2000);
        String line = sanitizeLogField(safePayload.getOrDefault("line", ""), 100);
        String column = sanitizeLogField(safePayload.getOrDefault("column", ""), 100);
        String stack = sanitizeLogField(safePayload.getOrDefault("stack", ""), 12000);
        String timestamp = sanitizeLogField(safePayload.getOrDefault("timestamp", ""), 100);
        String page = sanitizeLogField(safePayload.getOrDefault("page", ""), 2000);
        String userAgent = sanitizeLogField(safePayload.getOrDefault("userAgent", ""), 1000);
        String sequence = sanitizeLogField(safePayload.getOrDefault("sequence", ""), 100);
        String requestUri = sanitizeLogField(request != null ? request.getRequestURI() : "", 500);
        String remoteAddr = sanitizeLogField(resolveClientAddress(request), 200);

        clientErrorLog.warn("Client JS error kind={} message={} source={} line={} column={} page={} sequence={} timestamp={} remote={} uri={} userAgent={} stack={}",
                kind, message, source, line, column, page, sequence, timestamp, remoteAddr, requestUri, userAgent, stack);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logged", true);
        return ResponseEntity.ok(result);
    }

    private String resolveClientAddress(HttpServletRequest request) {
        if (request == null) return "";
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int commaIndex = forwarded.indexOf(',');
            return commaIndex >= 0 ? forwarded.substring(0, commaIndex).trim() : forwarded.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }

    private String sanitizeLogField(Object value, int maxLength) {
        if (value == null) return "";
        String text = String.valueOf(value)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...(trimmed)";
    }

    // ======================== PRIVATE ========================

    private String buildValidationReport(PdfSession session, List<ValidationIssue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("PDFalyzer Studio Validation Report\n==========================\n");
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
