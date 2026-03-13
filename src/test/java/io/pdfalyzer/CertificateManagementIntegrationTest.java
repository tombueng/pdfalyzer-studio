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
class CertificateManagementIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void generateSelfSignedAndListKeys() throws Exception {
        String sessionId = uploadPdfAndGetSessionId();

        // Generate self-signed certificate
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
        assertNotNull(genResp.getBody());
        assertTrue((Boolean) genResp.getBody().get("readyToSign"), "Self-signed cert should be ready to sign");
        assertTrue((Boolean) genResp.getBody().get("hasPrivateKey"));
        assertTrue((Boolean) genResp.getBody().get("hasCertificate"));
        String subjectDN = (String) genResp.getBody().get("subjectDN");
        assertNotNull(subjectDN);
        assertTrue(subjectDN.contains("Test Signer"), "Subject DN should contain CN");

        String keyId = (String) genResp.getBody().get("sessionKeyId");
        assertNotNull(keyId);

        // List keys
        ResponseEntity<List<Map<String, Object>>> listResp = restTemplate.exchange(
                "/api/signing/{sessionId}/keys",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {},
                sessionId
        );

        assertEquals(HttpStatus.OK, listResp.getStatusCode());
        assertNotNull(listResp.getBody());
        assertEquals(1, listResp.getBody().size());
        assertEquals(keyId, listResp.getBody().get(0).get("sessionKeyId"));

        // Get single key status
        ResponseEntity<Map<String, Object>> statusResp = restTemplate.exchange(
                "/api/signing/{sessionId}/key/{keyId}",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId, keyId
        );

        assertEquals(HttpStatus.OK, statusResp.getStatusCode());
        assertTrue((Boolean) statusResp.getBody().get("readyToSign"));

        // Export as PKCS12
        ResponseEntity<byte[]> exportResp = restTemplate.exchange(
                "/api/signing/{sessionId}/export-key/{keyId}?password=test123",
                HttpMethod.GET,
                null,
                byte[].class,
                sessionId, keyId
        );

        assertEquals(HttpStatus.OK, exportResp.getStatusCode());
        assertNotNull(exportResp.getBody());
        assertTrue(exportResp.getBody().length > 0, "PKCS12 export should have content");

        // Re-upload the exported PKCS12 as a new key set
        MultiValueMap<String, Object> uploadBody = new LinkedMultiValueMap<>();
        uploadBody.add("file", new ByteArrayResource(exportResp.getBody()) {
            @Override public String getFilename() { return "exported.p12"; }
        });
        uploadBody.add("password", "test123");

        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<Map<String, Object>> reuploadResp = restTemplate.exchange(
                "/api/signing/{sessionId}/upload-key",
                HttpMethod.POST,
                new HttpEntity<>(uploadBody, uploadHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId
        );

        assertEquals(HttpStatus.OK, reuploadResp.getStatusCode());
        assertTrue((Boolean) reuploadResp.getBody().get("readyToSign"), "Re-uploaded PKCS12 should be ready");
        assertTrue((Boolean) reuploadResp.getBody().get("hasPrivateKey"));

        // Delete the first key
        ResponseEntity<Map<String, Object>> deleteResp = restTemplate.exchange(
                "/api/signing/{sessionId}/key/{keyId}",
                HttpMethod.DELETE,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId, keyId
        );

        assertEquals(HttpStatus.OK, deleteResp.getStatusCode());

        // Verify list now has only 1 key
        ResponseEntity<List<Map<String, Object>>> listResp2 = restTemplate.exchange(
                "/api/signing/{sessionId}/keys",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {},
                sessionId
        );

        assertEquals(1, listResp2.getBody().size());
    }

    @Test
    void generateEcCertificate() throws Exception {
        String sessionId = uploadPdfAndGetSessionId();

        Map<String, Object> certRequest = Map.of(
                "commonName", "EC Signer",
                "keyAlgorithm", "EC",
                "keySize", 256,
                "validityDays", 30
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
        assertEquals("EC", genResp.getBody().get("keyAlgorithm"));
    }

    @Test
    void unknownSessionReturnsNotFound() {
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/api/signing/{sessionId}/keys",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                "00000000-0000-0000-0000-000000000000"
        );

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void deleteNonexistentKeyReturnsNotFound() throws Exception {
        String sessionId = uploadPdfAndGetSessionId();

        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/api/signing/{sessionId}/key/{keyId}",
                HttpMethod.DELETE,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sessionId, "nonexistent-key-id"
        );

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
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
