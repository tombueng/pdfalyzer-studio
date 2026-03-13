package io.pdfalyzer.web;

import io.pdfalyzer.model.*;
import io.pdfalyzer.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles all PDF-editing endpoints: COS attribute updates, form-field CRUD, etc.
 */
@RestController
@RequestMapping("/api")
public class EditApiController {

    private final PdfService pdfService;
    private final CosEditService cosEditService;
    private final PdfEditService pdfEditService;

    public EditApiController(PdfService pdfService,
                              CosEditService cosEditService,
                              PdfEditService pdfEditService) {
        this.pdfService = pdfService;
        this.cosEditService = cosEditService;
        this.pdfEditService = pdfEditService;
    }

    @PostMapping("/cos/{sessionId}/update")
    public ResponseEntity<Map<String, Object>> updateCosValue(
            @PathVariable("sessionId") String sessionId,
            @RequestBody CosUpdateRequest request) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = cosEditService.updateCosValue(bytes, request);
        pdfService.updateSessionPdf(sessionId, modified);
        return treeResponse(sessionId);
    }

    @PostMapping("/edit/{sessionId}/add-field")
    public ResponseEntity<Map<String, Object>> addFormField(
            @PathVariable("sessionId") String sessionId,
            @RequestBody FormFieldRequest request) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = pdfEditService.addFormField(bytes, request);
        pdfService.updateSessionPdf(sessionId, modified);
        return treeResponse(sessionId);
    }

    @PostMapping("/edit/{sessionId}/add-fields")
    public ResponseEntity<Map<String, Object>> addFormFields(
            @PathVariable("sessionId") String sessionId,
            @RequestBody List<FormFieldRequest> requests) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = pdfEditService.addFormFields(bytes, requests);
        pdfService.updateSessionPdf(sessionId, modified);
        return treeResponse(sessionId);
    }

    @DeleteMapping("/edit/{sessionId}/field/{fieldName}")
    public ResponseEntity<Map<String, Object>> deleteFormField(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("fieldName") String fieldName) throws IOException {
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = pdfEditService.deleteFormField(bytes, fieldName);
        pdfService.updateSessionPdf(sessionId, modified);
        return treeResponse(sessionId);
    }

    @PostMapping("/edit/{sessionId}/field/{fieldName}/value")
    public ResponseEntity<Map<String, Object>> setFieldValue(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("fieldName") String fieldName,
            @RequestBody Map<String, String> body) throws IOException {
        String value = body.getOrDefault("value", "");
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = pdfEditService.setFormFieldValue(bytes, fieldName, value);
        pdfService.updateSessionPdf(sessionId, modified);
        return treeResponse(sessionId);
    }

    @PostMapping("/edit/{sessionId}/field/{fieldName}/choices")
    public ResponseEntity<Map<String, Object>> setComboChoices(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("fieldName") String fieldName,
            @RequestBody Map<String, List<String>> body) throws IOException {
        List<String> choices = body.get("choices");
        if (choices == null) {
            return ResponseEntity.badRequest().<Map<String, Object>>build();
        }
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = pdfEditService.setComboChoices(bytes, fieldName, choices);
        pdfService.updateSessionPdf(sessionId, modified);
        return treeResponse(sessionId);
    }

    @PostMapping("/edit/{sessionId}/field/{fieldName}/rect")
    public ResponseEntity<Map<String, Object>> updateFieldRect(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("fieldName") String fieldName,
            @RequestBody Map<String, Double> body) throws IOException {
        Double x = body.get("x");
        Double y = body.get("y");
        Double width = body.get("width");
        Double height = body.get("height");
        if (x == null || y == null || width == null || height == null) {
            return ResponseEntity.badRequest().build();
        }
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = pdfEditService.updateFieldRect(bytes, fieldName, x, y, width, height);
        pdfService.updateSessionPdf(sessionId, modified);
        return treeResponse(sessionId);
    }

    @PostMapping("/edit/{sessionId}/fields/options")
    public ResponseEntity<Map<String, Object>> applyFieldOptions(
            @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Object> body) throws IOException {
        @SuppressWarnings("unchecked")
        List<String> fieldNames = (List<String>) body.get("fieldNames");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");
        if (fieldNames == null || fieldNames.isEmpty() || options == null) {
            return ResponseEntity.badRequest().build();
        }
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = pdfEditService.applyFieldOptions(bytes, fieldNames, options);
        pdfService.updateSessionPdf(sessionId, modified);
        return treeResponse(sessionId);
    }

    @PostMapping("/edit/{sessionId}/radio/{fieldName}/restructure")
    public ResponseEntity<Map<String, Object>> restructureRadioGroup(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("fieldName") String fieldName,
            @RequestBody Map<String, Object> body) throws IOException {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> options = (List<Map<String, String>>) body.get("options");
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldOptions = (Map<String, Object>) body.get("fieldOptions");
        if (options == null) return ResponseEntity.badRequest().build();
        byte[] bytes = pdfService.getSessionPdfBytes(sessionId);
        byte[] modified = pdfEditService.restructureRadioGroup(bytes, fieldName, options, fieldOptions);
        pdfService.updateSessionPdf(sessionId, modified);
        return treeResponse(sessionId);
    }

    private ResponseEntity<Map<String, Object>> treeResponse(String sessionId) {
        PdfSession session = pdfService.getSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("pageCount", session.getPageCount());
        result.put("tree", session.getTreeRoot());
        return ResponseEntity.ok(result);
    }
}
