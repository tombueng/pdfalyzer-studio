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

import io.pdfalyzer.model.CscCredential;
import io.pdfalyzer.model.CscProvider;
import io.pdfalyzer.model.KeyMaterialUploadResult;
import io.pdfalyzer.model.PdfSession;
import io.pdfalyzer.model.PendingSignatureData;
import io.pdfalyzer.model.SelfSignedCertRequest;
import io.pdfalyzer.model.SigningKeyMaterial;
import io.pdfalyzer.model.SigningRequest;
import io.pdfalyzer.model.TsaServer;
import io.pdfalyzer.service.CertificateService;
import io.pdfalyzer.service.CscApiClient;
import io.pdfalyzer.service.CscProviderRegistry;
import io.pdfalyzer.service.CscSigningService;
import io.pdfalyzer.service.PdfService;
import io.pdfalyzer.service.PdfSigningService;
import io.pdfalyzer.service.SelfSignedCertService;
import io.pdfalyzer.service.TsaProbeService;

@RestController
@RequestMapping("/api/signing")
public class SigningApiController {

    private final CertificateService certificateService;
    private final SelfSignedCertService selfSignedCertService;
    private final PdfSigningService pdfSigningService;
    private final PdfService pdfService;
    private final TsaProbeService tsaProbeService;
    private final CscProviderRegistry cscProviderRegistry;
    private final CscApiClient cscApiClient;
    private final CscSigningService cscSigningService;

    public SigningApiController(CertificateService certificateService,
                                SelfSignedCertService selfSignedCertService,
                                PdfSigningService pdfSigningService,
                                PdfService pdfService,
                                TsaProbeService tsaProbeService,
                                CscProviderRegistry cscProviderRegistry,
                                CscApiClient cscApiClient,
                                CscSigningService cscSigningService) {
        this.certificateService = certificateService;
        this.selfSignedCertService = selfSignedCertService;
        this.pdfSigningService = pdfSigningService;
        this.pdfService = pdfService;
        this.tsaProbeService = tsaProbeService;
        this.cscProviderRegistry = cscProviderRegistry;
        this.cscApiClient = cscApiClient;
        this.cscSigningService = cscSigningService;
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
                .tsaServerId(request.getTsaServerId())
                .tsaUrl(request.getTsaUrl())
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

    // ── TSA Server endpoints ────────────────────────────────────────────

    @GetMapping("/tsa/servers")
    public ResponseEntity<List<TsaServer>> listTsaServers() {
        return ResponseEntity.ok(tsaProbeService.getAllWithStatus());
    }

    @PostMapping("/tsa/probe")
    public ResponseEntity<Map<String, String>> probeAllTsa() {
        tsaProbeService.probeAllAsync();
        return ResponseEntity.ok(Map.of("status", "probing"));
    }

    @PostMapping("/tsa/probe/{serverId}")
    public ResponseEntity<Map<String, String>> probeSingleTsa(
            @PathVariable("serverId") String serverId) {
        tsaProbeService.probeSingleAsync(serverId);
        return ResponseEntity.ok(Map.of("status", "probing", "serverId", serverId));
    }

    @GetMapping("/tsa/server/{serverId}")
    public ResponseEntity<TsaServer> getTsaServer(
            @PathVariable("serverId") String serverId) {
        TsaServer server = tsaProbeService.getWithStatus(serverId);
        if (server == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(server);
    }

    // ── CSC Remote Signing endpoints ────────────────────────────────────

    @GetMapping("/csc/providers")
    public ResponseEntity<List<CscProvider>> listCscProviders() {
        return ResponseEntity.ok(cscProviderRegistry.listAll());
    }

    @GetMapping("/csc/provider/{providerId}")
    public ResponseEntity<CscProvider> getCscProvider(
            @PathVariable("providerId") String providerId) {
        CscProvider provider = cscProviderRegistry.get(providerId);
        if (provider == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(provider);
    }

    @PostMapping("/csc/provider")
    public ResponseEntity<CscProvider> addCscProvider(@RequestBody CscProvider provider) {
        return ResponseEntity.ok(cscProviderRegistry.addOrUpdate(provider));
    }

    @PostMapping("/csc/{providerId}/connect")
    public ResponseEntity<Map<String, Object>> connectCscProvider(
            @PathVariable("providerId") String providerId,
            @RequestBody Map<String, String> credentials) {
        CscProvider provider = cscProviderRegistry.get(providerId);
        if (provider == null) return ResponseEntity.notFound().build();

        String sessionId = credentials.get("sessionId");
        String username = credentials.get("username");
        String password = credentials.get("password");

        try {
            String token;
            if (username != null && !username.isBlank()) {
                // Resource Owner Password Grant
                token = cscApiClient.authenticate(provider, username, password);
            } else if (provider.getClientId() != null && !provider.getClientId().isBlank()) {
                // Client Credentials Grant
                token = cscApiClient.authenticateClientCredentials(provider);
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "No credentials provided"));
            }

            cscApiClient.storeToken(sessionId, providerId, token);
            provider.setStatus("connected");
            provider.setStatusMessage(null);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("providerId", providerId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            provider.setStatus("error");
            provider.setStatusMessage(e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "connected", false,
                    "error", e.getMessage()));
        }
    }

    @GetMapping("/csc/{sessionId}/{providerId}/credentials")
    public ResponseEntity<List<CscCredential>> listCscCredentials(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("providerId") String providerId) {
        CscProvider provider = cscProviderRegistry.get(providerId);
        if (provider == null) return ResponseEntity.notFound().build();

        String token = cscApiClient.getToken(sessionId, providerId);
        if (token == null) {
            return ResponseEntity.badRequest().build();
        }

        List<CscCredential> credentials = cscApiClient.listCredentials(provider, token);
        return ResponseEntity.ok(credentials);
    }

    @PostMapping("/csc/{sessionId}/{providerId}/send-otp")
    public ResponseEntity<Map<String, Boolean>> sendCscOtp(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("providerId") String providerId,
            @RequestBody Map<String, String> body) {
        CscProvider provider = cscProviderRegistry.get(providerId);
        if (provider == null) return ResponseEntity.notFound().build();

        String token = cscApiClient.getToken(sessionId, providerId);
        if (token == null) return ResponseEntity.badRequest().build();

        cscApiClient.sendOtp(provider, token, body.get("credentialId"));
        return ResponseEntity.ok(Map.of("sent", true));
    }

    @PostMapping("/csc/{sessionId}/{providerId}/sign")
    public ResponseEntity<Map<String, Object>> signWithCsc(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("providerId") String providerId,
            @RequestBody Map<String, Object> body) {
        CscProvider provider = cscProviderRegistry.get(providerId);
        if (provider == null) return ResponseEntity.notFound().build();

        String token = cscApiClient.getToken(sessionId, providerId);
        if (token == null) return ResponseEntity.badRequest().build();

        String credentialId = (String) body.get("credentialId");
        String fieldName = (String) body.get("fieldName");
        String otp = (String) body.get("otp");
        String reason = (String) body.get("reason");
        String location = (String) body.get("location");

        // Get credential info for cert chain
        CscCredential cred = cscApiClient.getCredentialInfo(provider, token, credentialId);

        // Determine signing algorithm OID based on key type
        String signAlgoOid = null;
        if (cred.getKeyAlgorithm() != null && cred.getKeyAlgorithm().contains("1.2.840.10045")) {
            signAlgoOid = "1.2.840.10045.4.3.2"; // SHA256withECDSA
        } else {
            signAlgoOid = "1.2.840.113549.1.1.11"; // SHA256withRSA
        }

        // Authorize credential (get SAD)
        String sad = cscApiClient.authorizeCredential(provider, token, credentialId, 1, null, otp);

        // Sign the PDF
        PdfSession session = pdfService.getSession(sessionId);
        byte[] signed = cscSigningService.signWithCsc(
                session.getPdfBytes(), fieldName,
                provider, token, credentialId, sad, signAlgoOid,
                cred.getCertificates(), reason, location);

        try {
            pdfService.updateSessionPdf(sessionId, signed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update session after CSC signing: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("signed", true);
        result.put("fieldName", fieldName);
        result.put("provider", provider.getName());
        result.put("pageCount", session.getPageCount());
        result.put("tree", session.getTreeRoot());
        return ResponseEntity.ok(result);
    }

    // ── Key export ───────────────────────────────────────────────────────

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
