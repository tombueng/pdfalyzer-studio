package io.pdfalyzer.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.pdfalyzer.model.KeyMaterialUploadResult;
import io.pdfalyzer.model.PdfSession;
import io.pdfalyzer.model.PendingSignatureData;
import io.pdfalyzer.model.SelfSignedCertRequest;
import io.pdfalyzer.model.SigningKeyMaterial;
import io.pdfalyzer.model.SigningRequest;
import io.pdfalyzer.service.CertificateService;
import io.pdfalyzer.service.PdfService;
import io.pdfalyzer.service.PdfSigningService;
import io.pdfalyzer.service.SelfSignedCertService;

@RestController
@RequestMapping("/api/signing")
public class SigningApiController {

    private final CertificateService certificateService;
    private final SelfSignedCertService selfSignedCertService;
    private final PdfSigningService pdfSigningService;
    private final PdfService pdfService;

    public SigningApiController(CertificateService certificateService,
                                SelfSignedCertService selfSignedCertService,
                                PdfSigningService pdfSigningService,
                                PdfService pdfService) {
        this.certificateService = certificateService;
        this.selfSignedCertService = selfSignedCertService;
        this.pdfSigningService = pdfSigningService;
        this.pdfService = pdfService;
    }

    @PostMapping("/{sessionId}/upload-key")
    public ResponseEntity<KeyMaterialUploadResult> uploadKeyMaterial(
            @PathVariable("sessionId") String sessionId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) throws Exception {
        byte[] bytes = file.getBytes();
        String filename = file.getOriginalFilename();
        KeyMaterialUploadResult result = certificateService.parseAndStore(sessionId, bytes, filename, password);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{sessionId}/upload-key/{sessionKeyId}")
    public ResponseEntity<KeyMaterialUploadResult> addToKeyMaterial(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("sessionKeyId") String sessionKeyId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) throws Exception {
        byte[] bytes = file.getBytes();
        String filename = file.getOriginalFilename();
        KeyMaterialUploadResult result = certificateService.addToExisting(sessionId, sessionKeyId, bytes, filename, password);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{sessionId}/keys")
    public ResponseEntity<List<KeyMaterialUploadResult>> listKeys(
            @PathVariable("sessionId") String sessionId) {
        return ResponseEntity.ok(certificateService.listKeyMaterials(sessionId));
    }

    @GetMapping("/{sessionId}/key/{sessionKeyId}")
    public ResponseEntity<KeyMaterialUploadResult> getKeyStatus(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("sessionKeyId") String sessionKeyId) {
        return ResponseEntity.ok(certificateService.getKeyMaterialStatus(sessionId, sessionKeyId));
    }

    @DeleteMapping("/{sessionId}/key/{sessionKeyId}")
    public ResponseEntity<Map<String, Boolean>> deleteKey(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("sessionKeyId") String sessionKeyId) {
        certificateService.deleteKeyMaterial(sessionId, sessionKeyId);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @PostMapping("/{sessionId}/generate-self-signed")
    public ResponseEntity<KeyMaterialUploadResult> generateSelfSigned(
            @PathVariable("sessionId") String sessionId,
            @RequestBody SelfSignedCertRequest request) {
        return ResponseEntity.ok(selfSignedCertService.generateAndStore(sessionId, request));
    }

    @PostMapping("/{sessionId}/prepare-signature")
    public ResponseEntity<PendingSignatureData> prepareSignature(
            @PathVariable("sessionId") String sessionId,
            @RequestBody SigningRequest request) {
        if (request.getFieldName() == null || request.getFieldName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getSessionKeyId() == null || request.getSessionKeyId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        SigningKeyMaterial material = certificateService.getKeyMaterial(sessionId, request.getSessionKeyId());
        if (!material.isReadyToSign()) {
            return ResponseEntity.badRequest().build();
        }

        PendingSignatureData pending = PendingSignatureData.builder()
                .pendingSignatureId(java.util.UUID.randomUUID().toString())
                .fieldName(request.getFieldName())
                .sessionKeyId(request.getSessionKeyId())
                .visualMode(request.getVisualMode())
                .displayName(request.getDisplayName())
                .fontName(request.getFontName())
                .imageDataBase64(request.getImageDataBase64())
                .drawnImageBase64(request.getDrawnImageBase64())
                .signMode(request.getSignMode())
                .docMdpLevel(request.getDocMdpLevel())
                .padesProfile(request.getPadesProfile())
                .excludedFields(request.getExcludedFields())
                .reason(request.getReason())
                .location(request.getLocation())
                .contactInfo(request.getContactInfo())
                .biometricData(request.getBiometricData())
                .biometricFormat(request.getBiometricFormat())
                .createdAt(System.currentTimeMillis())
                .certSubjectDN(material.getCertificate() != null
                        ? material.getCertificate().getSubjectX500Principal().getName()
                        : null)
                .certIssuerDN(material.getCertificate() != null
                        ? material.getCertificate().getIssuerX500Principal().getName()
                        : null)
                .build();

        return ResponseEntity.ok(pending);
    }

    @PostMapping("/{sessionId}/apply-signature")
    public ResponseEntity<Map<String, Object>> applySignature(
            @PathVariable("sessionId") String sessionId,
            @RequestBody PendingSignatureData pending) {
        if (pending.getPendingSignatureId() == null || pending.getSessionKeyId() == null) {
            return ResponseEntity.badRequest().build();
        }

        SigningKeyMaterial material = certificateService.getKeyMaterial(sessionId, pending.getSessionKeyId());
        if (!material.isReadyToSign()) {
            return ResponseEntity.badRequest().build();
        }

        PdfSession session = pdfService.getSession(sessionId);
        byte[] signed = pdfSigningService.signDocument(session.getPdfBytes(), pending, material);
        try {
            pdfService.updateSessionPdf(sessionId, signed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update session after signing: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("pageCount", session.getPageCount());
        result.put("tree", session.getTreeRoot());
        result.put("signed", true);
        result.put("fieldName", pending.getFieldName());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{sessionId}/export-key/{sessionKeyId}")
    public ResponseEntity<byte[]> exportKey(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("sessionKeyId") String sessionKeyId,
            @RequestParam(value = "password", defaultValue = "changeit") String password) {
        byte[] pkcs12 = selfSignedCertService.exportAsPkcs12(sessionId, sessionKeyId, password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", "certificate.p12");
        return ResponseEntity.ok().headers(headers).body(pkcs12);
    }
}
