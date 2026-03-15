package io.pdfalyzer.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.pdfalyzer.model.SignatureAnalysisResult;
import io.pdfalyzer.service.PdfService;
import io.pdfalyzer.service.SessionService;
import io.pdfalyzer.service.SignatureAnalysisService;
import io.pdfalyzer.service.TrustListService;
import io.pdfalyzer.service.TrustValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for on-demand trust validation of PDF signatures.
 * Provides endpoints to trigger validation, poll progress, and retrieve results.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/trust")
public class TrustValidationApiController {

    private final TrustValidationService trustValidationService;
    private final TrustListService trustListService;
    private final SignatureAnalysisService signatureAnalysisService;
    private final PdfService pdfService;
    private final SessionService sessionService;

    /**
     * Trigger on-demand trust validation for a session's signatures.
     * Downloads EUTL/AATL trust lists, validates certificate chains, checks revocation.
     * Returns immediately — poll /status for progress.
     */
    @PostMapping("/{sessionId}/validate")
    public ResponseEntity<Map<String, String>> triggerValidation(@PathVariable String sessionId) {
        try {
            var session = sessionService.getSession(sessionId);
            if (session == null) {
                return ResponseEntity.notFound().build();
            }

            // Check if validation is already in progress
            TrustValidationService.ValidationProgress existing = trustValidationService.getProgress(sessionId);
            if (existing != null && "STARTED".equals(existing.getStatus())
                    || existing != null && "COLLECTING_CERTS".equals(existing.getStatus())
                    || existing != null && "FETCHING_TRUST_LISTS".equals(existing.getStatus())
                    || existing != null && "CHECKING_DSS".equals(existing.getStatus())
                    || existing != null && "VALIDATING".equals(existing.getStatus())) {
                Map<String, String> response = new HashMap<>();
                response.put("status", "ALREADY_IN_PROGRESS");
                response.put("message", "Validation is already running");
                return ResponseEntity.ok(response);
            }

            byte[] pdfBytes = pdfService.getSessionPdfBytes(sessionId);
            SignatureAnalysisResult basicResult = signatureAnalysisService.analyzeSignatures(pdfBytes);

            trustValidationService.startValidation(sessionId, pdfBytes, basicResult);

            Map<String, String> response = new HashMap<>();
            response.put("status", "STARTED");
            response.put("message", "Trust validation started");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to start trust validation for session {}", sessionId, e);
            Map<String, String> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Poll trust validation progress for a session.
     */
    @GetMapping("/{sessionId}/status")
    public ResponseEntity<Map<String, Object>> getValidationStatus(@PathVariable String sessionId) {
        TrustValidationService.ValidationProgress progress = trustValidationService.getProgress(sessionId);
        if (progress == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "NOT_STARTED");
            response.put("message", "No validation has been triggered for this session");
            return ResponseEntity.ok(response);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", progress.getStatus());
        response.put("message", progress.getMessage());
        response.put("eutlStatus", progress.getEutlStatus() != null ? progress.getEutlStatus() : trustListService.getEutlStatus());
        response.put("aatlStatus", progress.getAatlStatus() != null ? progress.getAatlStatus() : trustListService.getAatlStatus());
        response.put("completed", "COMPLETED".equals(progress.getStatus()) || "ERROR".equals(progress.getStatus()));
        return ResponseEntity.ok(response);
    }

    /**
     * Get the enhanced signature analysis result after validation completes.
     */
    @GetMapping("/{sessionId}/result")
    public ResponseEntity<SignatureAnalysisResult> getValidatedResult(@PathVariable String sessionId) {
        SignatureAnalysisResult result = trustValidationService.getValidatedResult(sessionId);
        if (result == null) {
            TrustValidationService.ValidationProgress progress = trustValidationService.getProgress(sessionId);
            if (progress != null && !"COMPLETED".equals(progress.getStatus())) {
                return ResponseEntity.accepted().build(); // Still processing
            }
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Diagnostic endpoint: list loaded EUTL trust anchors, optionally filtered by keyword.
     * Example: GET /api/trust/anchors?filter=d-trust
     */
    @GetMapping("/anchors")
    public ResponseEntity<Map<String, Object>> listAnchors(
            @RequestParam(required = false, defaultValue = "") String filter) {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, String>> anchors = trustListService.listEutlAnchors(filter);
        response.put("totalLoaded", trustListService.listEutlAnchors(null).size());
        response.put("matchCount", anchors.size());
        response.put("filter", filter);
        response.put("anchors", anchors);
        return ResponseEntity.ok(response);
    }

    /**
     * Diagnostic endpoint: show LOTL country→TSL URL map.
     */
    @GetMapping("/lotl-countries")
    public ResponseEntity<Map<String, String>> lotlCountries() {
        return ResponseEntity.ok(trustListService.getLotlCountries());
    }
}
