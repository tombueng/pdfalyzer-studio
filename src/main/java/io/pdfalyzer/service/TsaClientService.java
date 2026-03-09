package io.pdfalyzer.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Obtains RFC 3161 timestamp tokens from a TSA server.
 * Used during PDF signing to embed a trusted timestamp (PAdES-B-T profile).
 */
@Service
@Slf4j
public class TsaClientService {

    @Value("${pdfalyzer.tsa.request-timeout-ms:15000}")
    private int requestTimeoutMs;

    private final HttpClient httpClient;

    public TsaClientService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(15000))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Obtain a timestamp token for the given message imprint (signature value).
     *
     * @param tsaUrl URL of the RFC 3161 TSA endpoint
     * @param signatureBytes the CMS signature bytes to timestamp
     * @return the encoded timestamp token bytes (DER)
     */
    public byte[] getTimestampToken(String tsaUrl, byte[] signatureBytes) throws Exception {
        // Hash the signature value
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(signatureBytes);

        // Build RFC 3161 request
        TimeStampRequestGenerator gen = new TimeStampRequestGenerator();
        gen.setCertReq(true); // request TSA certificate in response
        TimeStampRequest tsReq = gen.generate(NISTObjectIdentifiers.id_sha256, hash);
        byte[] reqBytes = tsReq.getEncoded();

        log.info("Requesting timestamp from TSA: {}", tsaUrl);
        long start = System.currentTimeMillis();

        // Send HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tsaUrl))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("Content-Type", "application/timestamp-query")
                .POST(HttpRequest.BodyPublishers.ofByteArray(reqBytes))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        long elapsed = System.currentTimeMillis() - start;

        if (response.statusCode() != 200) {
            throw new IllegalStateException("TSA returned HTTP " + response.statusCode() + " from " + tsaUrl);
        }

        byte[] respBytes = response.body();
        if (respBytes == null || respBytes.length == 0) {
            throw new IllegalStateException("TSA returned empty response from " + tsaUrl);
        }

        // Parse and validate
        TimeStampResponse tsResponse = new TimeStampResponse(respBytes);
        tsResponse.validate(tsReq);

        int status = tsResponse.getStatus();
        if (status != 0 && status != 1) {
            String msg = tsResponse.getStatusString() != null ? tsResponse.getStatusString() : "status=" + status;
            throw new IllegalStateException("TSA returned error: " + msg);
        }

        TimeStampToken token = tsResponse.getTimeStampToken();
        if (token == null) {
            throw new IllegalStateException("TSA response contains no timestamp token");
        }

        byte[] tokenBytes = token.getEncoded();
        log.info("Timestamp obtained from {} in {}ms ({} bytes)", tsaUrl, elapsed, tokenBytes.length);
        return tokenBytes;
    }
}
