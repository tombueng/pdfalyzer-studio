package io.pdfalyzer;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SigningIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void fullSigningFlow_generateCert_prepareAndApply() throws Exception {
        String sessionId = uploadPdfAndGetSessionId();

        // 1. Generate self-signed certificate
        Map<String, Object> certRequest = Map.of(
                "commonName", "Test Signer",
                "organization", "PDFalyzer Test",
                "country", "CH",
                "validityDays", 365,
                "keyAlgorithm", "RSA",
                "keySize", 2048
        );

        ResponseEntity<Map<String, Object>> genResp = restTemplate.exchange(
                "/api/signing/{sessionId}/generate-self-signed",
                HttpMethod.POST,
                new HttpEntity<>(certRequest, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId
        );

        assertEquals(HttpStatus.OK, genResp.getStatusCode());
        assertTrue((Boolean) genResp.getBody().get("readyToSign"));
        String keyId = (String) genResp.getBody().get("sessionKeyId");
        assertNotNull(keyId);

        // 2. Prepare signature
        Map<String, Object> sigRequest = Map.of(
                "fieldName", "signature_field",
                "sessionKeyId", keyId,
                "visualMode", "text",
                "displayName", "Test Signer",
                "signMode", "approval",
                "padesProfile", "B-B"
        );

        ResponseEntity<Map<String, Object>> prepResp = restTemplate.exchange(
                "/api/signing/{sessionId}/prepare-signature",
                HttpMethod.POST,
                new HttpEntity<>(sigRequest, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId
        );

        assertEquals(HttpStatus.OK, prepResp.getStatusCode());
        assertNotNull(prepResp.getBody());
        String pendingSigId = (String) prepResp.getBody().get("pendingSignatureId");
        assertNotNull(pendingSigId);
        assertEquals("signature_field", prepResp.getBody().get("fieldName"));
        assertNotNull(prepResp.getBody().get("certSubjectDN"));

        // 3. Apply signature
        ResponseEntity<Map<String, Object>> applyResp = restTemplate.exchange(
                "/api/signing/{sessionId}/apply-signature",
                HttpMethod.POST,
                new HttpEntity<>(prepResp.getBody(), jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId
        );

        assertEquals(HttpStatus.OK, applyResp.getStatusCode());
        assertNotNull(applyResp.getBody());
        assertTrue((Boolean) applyResp.getBody().get("signed"));
        assertEquals("signature_field", applyResp.getBody().get("fieldName"));
        assertNotNull(applyResp.getBody().get("tree"), "Tree should be returned after signing");

        // 4. Verify via signatures analysis endpoint
        ResponseEntity<Map<String, Object>> sigAnalysis = restTemplate.exchange(
                "/api/signatures/{sessionId}",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId
        );

        assertEquals(HttpStatus.OK, sigAnalysis.getStatusCode());
        assertNotNull(sigAnalysis.getBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signatures = (List<Map<String, Object>>) sigAnalysis.getBody().get("signatures");
        assertNotNull(signatures);

        // Find our signed field
        Map<String, Object> signedField = null;
        for (Map<String, Object> sig : signatures) {
            if ("signature_field".equals(sig.get("fieldName")) || "signature_field".equals(sig.get("fullyQualifiedName"))) {
                signedField = sig;
                break;
            }
        }
        assertNotNull(signedField, "Signed field should appear in signature analysis");
        assertTrue((Boolean) signedField.get("signed"), "Field should be marked as signed");
        assertNotNull(signedField.get("subjectDN"), "Subject DN should be present");
    }

    @Test
    void applySignature_invalidField_fails() throws Exception {
        String sessionId = uploadPdfAndGetSessionId();

        // Generate cert
        Map<String, Object> certRequest = Map.of(
                "commonName", "Test",
                "keyAlgorithm", "RSA",
                "keySize", 2048,
                "validityDays", 30
        );

        ResponseEntity<Map<String, Object>> genResp = restTemplate.exchange(
                "/api/signing/{sessionId}/generate-self-signed",
                HttpMethod.POST,
                new HttpEntity<>(certRequest, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId
        );
        String keyId = (String) genResp.getBody().get("sessionKeyId");

        // Try to apply signature on a non-existent field
        Map<String, Object> sigData = Map.of(
                "pendingSignatureId", "test-id",
                "fieldName", "nonexistent_field",
                "sessionKeyId", keyId,
                "visualMode", "invisible",
                "signMode", "approval",
                "padesProfile", "B-B",
                "docMdpLevel", 2,
                "createdAt", System.currentTimeMillis(),
                "orderIndex", 0
        );

        ResponseEntity<Map<String, Object>> applyResp = restTemplate.exchange(
                "/api/signing/{sessionId}/apply-signature",
                HttpMethod.POST,
                new HttpEntity<>(sigData, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId
        );

        // Should fail with 500 (signing service throws IllegalStateException)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, applyResp.getStatusCode());
    }

    @Test
    void prepareSignature_missingKey_fails() throws Exception {
        String sessionId = uploadPdfAndGetSessionId();

        Map<String, Object> sigRequest = Map.of(
                "fieldName", "signature_field",
                "sessionKeyId", "nonexistent-key",
                "visualMode", "invisible",
                "signMode", "approval",
                "padesProfile", "B-B"
        );

        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/api/signing/{sessionId}/prepare-signature",
                HttpMethod.POST,
                new HttpEntity<>(sigRequest, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId
        );

        // Should fail — key not found
        assertNotEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String uploadPdfAndGetSessionId() throws Exception {
        byte[] pdfBytes;
        try (java.io.InputStream in = new ClassPathResource("sample-pdfs/test.pdf").getInputStream()) {
            pdfBytes = StreamUtils.copyToByteArray(in);
        }

        ByteArrayResource filePart = new ByteArrayResource(pdfBytes) {
            @Override public String getFilename() { return "test.pdf"; }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);

        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/api/upload", HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return (String) resp.getBody().get("sessionId");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
