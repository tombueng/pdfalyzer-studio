package io.pdfalyzer.web;

import io.pdfalyzer.model.PdfSession;
import io.pdfalyzer.service.PdfService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Handles resource download, preview and attachment endpoints.
 */
@RestController
@RequestMapping("/api")
public class ResourceApiController {

    private final PdfService pdfService;

    public ResourceApiController(PdfService pdfService) {
        this.pdfService = pdfService;
    }

    @GetMapping("/resource/{sessionId}/{objNum}/{genNum}")
    public ResponseEntity<byte[]> getResource(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("objNum") int objNum,
            @PathVariable("genNum") int genNum,
            @RequestParam(name = "keyPath", required = false) String keyPath,
            @RequestParam(name = "inline", defaultValue = "false") boolean inline)
            throws IOException {
        PdfSession session = pdfService.getSession(sessionId);
        try (PDDocument doc = Loader.loadPDF(session.getPdfBytes())) {
            COSDocument cosDoc = doc.getDocument();
            COSObject cosObj = cosDoc.getObjectFromPool(new COSObjectKey(objNum, genNum));
            if (cosObj == null || cosObj.getObject() == null) return ResponseEntity.notFound().build();

            COSStream stream = resolveStream(cosObj.getObject(), keyPath);
            if (stream == null) return ResponseEntity.badRequest().body(new byte[0]);

            return buildResourceResponse(stream, doc, objNum, genNum, inline);
        }
    }

    @GetMapping("/attachment/{sessionId}/{fileName}")
    public ResponseEntity<byte[]> getAttachment(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("fileName") String fileName,
            @RequestParam(name = "inline", defaultValue = "false") boolean inline) throws IOException {
        PdfSession session = pdfService.getSession(sessionId);
        try (PDDocument doc = Loader.loadPDF(session.getPdfBytes())) {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            PDDocumentNameDictionary names = catalog.getNames();
            if (names == null) return ResponseEntity.notFound().build();
            PDEmbeddedFilesNameTreeNode efTree = names.getEmbeddedFiles();
            if (efTree == null) return ResponseEntity.notFound().build();

            Map<String, PDComplexFileSpecification> fileMap = efTree.getNames();
            PDComplexFileSpecification spec = (fileMap != null) ? fileMap.get(fileName) : null;
            if (spec == null) return ResponseEntity.notFound().build();

            PDEmbeddedFile ef = spec.getEmbeddedFile();
            if (ef == null) ef = spec.getEmbeddedFileUnicode();
            if (ef == null) return ResponseEntity.notFound().build();

            byte[] data;
            try (java.io.InputStream is = ef.createInputStream()) {
                data = is.readAllBytes();
            }

                String mime = ef.getSubtype() != null ? ef.getSubtype() : guessMimeFromFilename(fileName);
                String disposition = inline ? "inline" : "attachment";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mime))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition + "; filename=\"" + sanitizeFilename(fileName) + "\"")
                    .contentLength(data.length)
                    .body(data);
        }
    }

    // ======================== HELPERS ========================

    private COSStream resolveStream(COSBase base, String keyPath) {
        if (base instanceof COSStream) return (COSStream) base;
        if (keyPath != null && !keyPath.isEmpty() && base instanceof COSDictionary) {
            try {
                COSDictionary current = (COSDictionary) base;
                java.util.List<String> segments = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(keyPath,
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
                for (String segment : segments) {
                    COSBase next = current.getDictionaryObject(segment);
                    if (next instanceof COSStream) return (COSStream) next;
                    if (next instanceof COSDictionary) current = (COSDictionary) next;
                    else break;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private ResponseEntity<byte[]> buildResourceResponse(COSStream stream, PDDocument doc,
                                                           int objNum, int genNum,
                                                           boolean inline) throws IOException {
        byte[] data;
        String mediaType = "application/octet-stream";
        String filename = "obj-" + objNum + "-" + genNum + ".bin";

        PDImageXObject imageXobj = findImageForStream(stream, doc);
        if (imageXobj != null) {
            BufferedImage bi = imageXobj.getImage();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", baos);
            data = baos.toByteArray();
            mediaType = "image/png";
            filename = "image-" + objNum + "-" + genNum + ".png";
        } else {
            String subtype = stream.getNameAsString(COSName.SUBTYPE);
            COSName filterName = stream.getCOSName(COSName.FILTER);
            String filter = filterName != null ? filterName.getName() : null;
            if ("XML".equalsIgnoreCase(subtype)) {
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
        String disp = inline ? "inline" : "attachment";
        headers.set(HttpHeaders.CONTENT_DISPOSITION, disp + "; filename=\"" + filename + "\"");
        return ResponseEntity.ok().headers(headers).body(data);
    }

    private PDImageXObject findImageForStream(COSStream target, PDDocument doc) throws IOException {
        for (PDPage page : doc.getPages()) {
            PDResources res = page.getResources();
            if (res == null) continue;
            for (COSName name : res.getXObjectNames()) {
                PDXObject xobj = res.getXObject(name);
                if (xobj instanceof PDImageXObject) {
                    PDImageXObject img = (PDImageXObject) xobj;
                    if (img.getCOSObject() == target) return img;
                    if (img.getMask() != null && img.getMask().getCOSObject() == target) return img;
                    if (img.getSoftMask() != null && img.getSoftMask().getCOSObject() == target)
                        return img;
                }
            }
        }
        return null;
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private String guessMimeFromFilename(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }
}
