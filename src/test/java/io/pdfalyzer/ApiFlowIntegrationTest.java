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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiFlowIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void uploadThenFetchPdfBySessionIdReturnsPdfBytes() throws IOException {
        ResponseEntity<Map> uploadResp = uploadTestPdf();
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

    private ResponseEntity<Map> uploadTestPdf() throws IOException {
        byte[] pdfBytes;
        try (var in = new ClassPathResource("test.pdf").getInputStream()) {
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
        return restTemplate.postForEntity("/api/upload", request, Map.class);
    }
}
