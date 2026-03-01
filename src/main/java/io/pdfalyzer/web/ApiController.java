package io.pdfalyzer.web;

import io.pdfalyzer.model.*;
import io.pdfalyzer.service.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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

    @GetMapping("/resource/{sessionId}/{objNum}/{genNum}")
    public ResponseEntity<byte[]> getResource(
            @PathVariable String sessionId,
            @PathVariable int objNum,
            @PathVariable int genNum,
                @RequestParam(name = "keyPath", required = false) String keyPath,
            @RequestParam(name = "inline", defaultValue = "false") boolean inline) throws IOException {
        PdfSession session = pdfService.getSession(sessionId);
        byte[] pdfBytes = session.getPdfBytes();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            COSDocument cosDoc = doc.getDocument();
            COSObjectKey key = new COSObjectKey(objNum, genNum);
            COSObject cosObj = cosDoc.getObjectFromPool(key);
            if (cosObj == null || cosObj.getObject() == null) {
                return ResponseEntity.notFound().build();
            }
            COSBase base = cosObj.getObject();
            
                // If base is not a stream but we have a keyPath, try to find the stream via keyPath
                COSStream stream = null;
                if (base instanceof COSStream) {
                    stream = (COSStream) base;
                } else if (keyPath != null && !keyPath.isEmpty() && base instanceof org.apache.pdfbox.cos.COSDictionary) {
                    // keyPath is JSON array like ["Resources", "XObject", "/ImageName"]
                    // Use it to navigate from page dictionary to the actual stream
                    try {
                        org.apache.pdfbox.cos.COSDictionary current = (org.apache.pdfbox.cos.COSDictionary) base;
                        java.util.List<String> pathSegments = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                            keyPath, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
                    
                        for (String segment : pathSegments) {
                            COSBase next = current.getDictionaryObject(segment);
                            if (next == null) break;
                            if (next instanceof org.apache.pdfbox.cos.COSDictionary) {
                                current = (org.apache.pdfbox.cos.COSDictionary) next;
                            } else if (next instanceof COSStream) {
                                stream = (COSStream) next;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        // keyPath navigation failed, let it fall through to error
                    }
            }
            
                if (stream == null) {
                    return ResponseEntity.badRequest().body(new byte[0]);
                }
            byte[] data;
            String mediaType = "application/octet-stream";
            String filename = "obj-" + objNum + "-" + genNum + ".bin";

            // try to map this stream to a PDImageXObject (handles masks and raw samples)
            PDImageXObject imageXobj = findImageForStream(stream, doc);
            if (imageXobj != null) {
                // render to PNG regardless of original encoding to ensure validity
                BufferedImage bi = imageXobj.getImage();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(bi, "png", baos);
                data = baos.toByteArray();
                mediaType = "image/png";
                filename = "image-" + objNum + "-" + genNum + ".png";
            } else {
                // fallback to simple stream export
                String subtype = stream.getNameAsString(org.apache.pdfbox.cos.COSName.SUBTYPE);
                org.apache.pdfbox.cos.COSName filterName = stream.getCOSName(org.apache.pdfbox.cos.COSName.FILTER);
                String filter = filterName != null ? filterName.getName() : null;
                if ("XML".equalsIgnoreCase(subtype)) {
                    // deliver raw xml with proper media type
                    mediaType = "application/xml";
                    filename = "stream-" + objNum + "-" + genNum + ".xml";
                } else if ("Image".equals(subtype)) {
                    if ("DCTDecode".equals(filter)) {
                        mediaType = "image/jpeg";
                        filename = "image-" + objNum + "-" + genNum + ".jpg";
                    } else if ("JPXDecode".equals(filter)) {
                        mediaType = "image/jpx";
                        filename = "image-" + objNum + "-" + genNum + ".jp2";
                    } else {
                        mediaType = "image/png";
                        filename = "image-" + objNum + "-" + genNum + ".png";
                    }
                }
                try (java.io.InputStream is = stream.createInputStream()) {
                    data = is.readAllBytes();
                }
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mediaType));
            headers.setContentLength(data.length);
            if (inline) {
                headers.set(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"");
            } else {
                headers.set(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"");
            }
            return ResponseEntity.ok().headers(headers).body(data);
        }
    }

    /**
     * Walk pages/resources to locate an image object that uses or references the
     * given COSStream.  If the stream is a mask of a larger image, this returns
     * the parent image so clients always get the full picture.
     */
    private PDImageXObject findImageForStream(COSStream target, PDDocument doc) throws IOException {
        for (PDPage page : doc.getPages()) {
            PDResources res = page.getResources();
            if (res == null) continue;
            for (COSName name : res.getXObjectNames()) {
                PDXObject xobj = res.getXObject(name);
                if (xobj instanceof PDImageXObject) {
                    PDImageXObject img = (PDImageXObject) xobj;
                    COSStream s = img.getCOSObject();
                    if (s == target) return img;
                    if (img.getMask() != null && img.getMask().getCOSObject() == target) return img;
                    if (img.getSoftMask() != null && img.getSoftMask().getCOSObject() == target) return img;
                }
            }
        }
        return null;
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
