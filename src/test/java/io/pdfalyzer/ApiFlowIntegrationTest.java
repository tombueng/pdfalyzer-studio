package io.pdfalyzer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiFlowIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void licenseOverviewPageReturnsOk() {
        ResponseEntity<String> response = restTemplate.getForEntity("/license", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Licenses and Notices"),
                "License overview page should render expected heading");
    }

    @Test
    void uploadThenFetchPdfBySessionIdReturnsPdfBytes() throws IOException {
        ResponseEntity<Map<String, Object>> uploadResp = uploadTestPdf();
        assertEquals(HttpStatus.OK, uploadResp.getStatusCode());
        assertNotNull(uploadResp.getBody());

        Object sid = uploadResp.getBody().get("sessionId");
        assertNotNull(sid, "upload response must contain sessionId");

        ResponseEntity<byte[]> pdfResp = restTemplate.getForEntity("/api/pdf/{sessionId}", byte[].class, sid);

        assertEquals(HttpStatus.OK, pdfResp.getStatusCode(),
                "Fresh sessionId from /api/upload must be valid for /api/pdf/{sessionId}");
        assertNotNull(pdfResp.getHeaders().getContentType());
        assertEquals(MediaType.APPLICATION_PDF, pdfResp.getHeaders().getContentType());
        assertNotNull(pdfResp.getBody());
        assertTrue(pdfResp.getBody().length > 0, "PDF response must contain bytes");
    }

    @Test
    void unknownSessionOnPdfEndpointReturnsNotFoundNotBadRequest() {
        ResponseEntity<byte[]> pdfResp = restTemplate.getForEntity("/api/pdf/{sessionId}", byte[].class,
                "00000000-0000-0000-0000-000000000000");

        assertEquals(HttpStatus.NOT_FOUND, pdfResp.getStatusCode(),
                "Unknown sessions should be 404, not 400");
    }

        @Test
        void uploadThenRunVeraPdfValidationDoesNotReturnReleaseDetailsUnmarshalError() throws IOException {
        ResponseEntity<Map<String, Object>> uploadResp = uploadTestPdf();
        assertEquals(HttpStatus.OK, uploadResp.getStatusCode());
        assertNotNull(uploadResp.getBody());

        Object sid = uploadResp.getBody().get("sessionId");
        assertNotNull(sid, "upload response must contain sessionId");

        ResponseEntity<Map<String, Object>> validateResp = restTemplate.exchange(
            "/api/validate/{sessionId}/verapdf",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<Map<String, Object>>() {},
            sid
        );

        assertEquals(HttpStatus.OK, validateResp.getStatusCode());
        assertNotNull(validateResp.getBody());
        String report = String.valueOf(validateResp.getBody().getOrDefault("report", ""));
        assertFalse(report.contains("Unmarshalling exception when streaming releaseDetails"),
            "veraPDF report should not include releaseDetails unmarshalling failure");
        assertEquals("xml", String.valueOf(validateResp.getBody().get("reportFormat")).toLowerCase(),
            "veraPDF response should provide the most detailed XML report format");
        assertTrue(report.length() > 200,
            "veraPDF report should be detailed and not reduced to a tiny summary payload");
        assertTrue(report.contains("<validationReport") || report.contains("<rule"),
            "veraPDF report should include per-validation details, not only batch summary");
        }

        @Test
        void clientErrorsEndpointAcceptsBrowserErrorPayload() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", "2026-03-01T00:00:00Z");
        payload.put("kind", "error");
        payload.put("message", "Synthetic test error");
        payload.put("source", "app-edit-mode.js");
        payload.put("line", "342");
        payload.put("column", "34");
        payload.put("stack", "TypeError: synthetic");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/client-errors",
            HttpMethod.POST,
            request,
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(Boolean.TRUE, response.getBody().get("logged"));
        }

    private ResponseEntity<Map<String, Object>> uploadTestPdf() throws IOException {
        byte[] pdfBytes;
        try (java.io.InputStream in = new ClassPathResource("test.pdf").getInputStream()) {
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

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        return restTemplate.exchange("/api/upload", HttpMethod.POST, request, new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
