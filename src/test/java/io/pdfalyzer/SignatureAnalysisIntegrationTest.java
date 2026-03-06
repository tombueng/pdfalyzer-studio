package io.pdfalyzer;

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

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignatureAnalysisIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void uploadResponseContainsHasSignaturesField() throws IOException {
        ResponseEntity<Map<String, Object>> uploadResp = uploadTestPdf();
        assertEquals(HttpStatus.OK, uploadResp.getStatusCode());
        assertNotNull(uploadResp.getBody());
        assertTrue(uploadResp.getBody().containsKey("hasSignatures"),
                "Upload response must include hasSignatures field");
    }

    @Test
    void signaturesEndpointReturnsValidStructure() throws IOException {
        ResponseEntity<Map<String, Object>> uploadResp = uploadTestPdf();
        assertEquals(HttpStatus.OK, uploadResp.getStatusCode());
        Object sid = uploadResp.getBody().get("sessionId");
        assertNotNull(sid);

        ResponseEntity<Map<String, Object>> sigResp = restTemplate.exchange(
                "/api/signatures/{sessionId}",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sid
        );

        assertEquals(HttpStatus.OK, sigResp.getStatusCode());
        assertNotNull(sigResp.getBody());
        assertTrue(sigResp.getBody().containsKey("totalSignatureFields"),
                "Response must include totalSignatureFields");
        assertTrue(sigResp.getBody().containsKey("signatures"),
                "Response must include signatures list");
        assertTrue(sigResp.getBody().get("signatures") instanceof List<?>,
                "signatures must be a list");

        int total = ((Number) sigResp.getBody().get("totalSignatureFields")).intValue();
        int signed = ((Number) sigResp.getBody().get("signedCount")).intValue();
        int unsigned = ((Number) sigResp.getBody().get("unsignedCount")).intValue();
        assertEquals(total, signed + unsigned,
                "signedCount + unsignedCount must equal totalSignatureFields");
    }

    @Test
    void signaturesEndpointReturnsCorrectFieldInfo() throws IOException {
        ResponseEntity<Map<String, Object>> uploadResp = uploadTestPdf();
        Object sid = uploadResp.getBody().get("sessionId");

        ResponseEntity<Map<String, Object>> sigResp = restTemplate.exchange(
                "/api/signatures/{sessionId}",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                sid
        );

        assertEquals(HttpStatus.OK, sigResp.getStatusCode());

        List<?> signatures = (List<?>) sigResp.getBody().get("signatures");
        assertNotNull(signatures, "signatures list must not be null");

        // Verify each signature entry has required fields
        for (Object entry : signatures) {
            Map<?, ?> sig = (Map<?, ?>) entry;
            assertTrue(sig.containsKey("fieldName"), "Each signature must have fieldName");
            assertTrue(sig.containsKey("signed"), "Each signature must have signed flag");
            assertTrue(sig.containsKey("signatureType"), "Each signature must have signatureType");
            assertTrue(sig.containsKey("validationStatus"), "Each signature must have validationStatus");

            String type = (String) sig.get("signatureType");
            assertTrue("approval".equals(type) || "certification".equals(type) || "unsigned".equals(type),
                    "signatureType must be approval, certification, or unsigned but was: " + type);
        }
    }

    @Test
    void unknownSessionOnSignaturesEndpointReturnsNotFound() {
        ResponseEntity<Map<String, Object>> sigResp = restTemplate.exchange(
                "/api/signatures/{sessionId}",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                "00000000-0000-0000-0000-000000000000"
        );
        assertEquals(HttpStatus.NOT_FOUND, sigResp.getStatusCode());
    }

    private ResponseEntity<Map<String, Object>> uploadTestPdf() throws IOException {
        byte[] pdfBytes;
        try (java.io.InputStream in = new ClassPathResource("sample-pdfs/test.pdf").getInputStream()) {
            pdfBytes = StreamUtils.copyToByteArray(in);
        }

        ByteArrayResource filePart = new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return "test.pdf";
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);

        return restTemplate.exchange("/api/upload", HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
