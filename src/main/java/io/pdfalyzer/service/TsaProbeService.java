package io.pdfalyzer.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.TsaServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Probes TSA servers in the background to check availability and response times.
 * Sends a minimal RFC 3161 timestamp request (SHA-256 hash of dummy data) to each server.
 */
@Service
@Slf4j
public class TsaProbeService {

    private final TsaServerRegistry registry;
    private final ConcurrentHashMap<String, TsaServer> probeResults = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final HttpClient httpClient;
    @Getter
    private volatile boolean probeStarted = false;

    @Value("${pdfalyzer.tsa.probe-timeout-ms:8000}")
    private int probeTimeoutMs;

    public TsaProbeService(TsaServerRegistry registry) {
        this.registry = registry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(8000))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PostConstruct
    public void init() {
        // Initialize probe results from registry with "unknown" status (no probing yet)
        for (TsaServer server : registry.getAllServers()) {
            probeResults.put(server.getId(), TsaServer.builder()
                    .id(server.getId())
                    .url(server.getUrl())
                    .name(server.getName())
                    .provider(server.getProvider())
                    .country(server.getCountry())
                    .category(server.getCategory())
                    .info(server.getInfo())
                    .qualifiedEidas(server.isQualifiedEidas())
                    .freeTier(server.isFreeTier())
                    .status("unknown")
                    .build());
        }
        log.info("TSA registry initialised with {} servers (probing deferred until UI request)", registry.size());
    }

    /**
     * Starts probing if it hasn't been triggered yet. Called lazily from the API layer
     * when the user first visits the signing page. Returns true if probing was started,
     * false if it was already in progress.
     */
    public boolean probeAllIfNotStarted() {
        if (probeStarted) return false;
        probeStarted = true;
        log.info("Starting background TSA probe for {} servers (triggered by UI)", registry.size());
        probeAllAsync();
        return true;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Probe all servers asynchronously in the background.
     */
    public void probeAllAsync() {
        for (TsaServer server : registry.getAllServers()) {
            TsaServer result = probeResults.get(server.getId());
            if (result != null) {
                result.setStatus("probing");
            }
            executor.submit(() -> probeSingle(server));
        }
    }

    /**
     * Probe a single server by ID.
     */
    public void probeSingleAsync(String serverId) {
        TsaServer server = registry.getById(serverId);
        if (server == null) return;

        TsaServer result = probeResults.get(serverId);
        if (result != null) {
            result.setStatus("probing");
        }
        executor.submit(() -> probeSingle(server));
    }

    /**
     * Get the current probe status of all servers, merged with registry metadata.
     */
    public List<TsaServer> getAllWithStatus() {
        List<TsaServer> result = new ArrayList<>();
        for (TsaServer server : registry.getAllServers()) {
            TsaServer probed = probeResults.get(server.getId());
            result.add(probed != null ? probed : server);
        }
        return result;
    }

    /**
     * Get the probe status of a single server.
     */
    public TsaServer getWithStatus(String serverId) {
        TsaServer probed = probeResults.get(serverId);
        if (probed != null) return probed;
        return registry.getById(serverId);
    }

    private void probeSingle(TsaServer server) {
        long startTime = System.currentTimeMillis();
        try {
            // Build a minimal RFC 3161 timestamp request
            byte[] tsRequest = buildTimestampRequest();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(server.getUrl()))
                    .timeout(Duration.ofMillis(probeTimeoutMs))
                    .header("Content-Type", "application/timestamp-query")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(tsRequest))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() == 200 && response.body() != null && response.body().length > 0) {
                // Try to parse the response to verify it's a valid TSP response
                try {
                    TimeStampResponse tsResponse = new TimeStampResponse(response.body());
                    int status = tsResponse.getStatus();
                    if (status == 0 || status == 1) { // granted or grantedWithMods
                        updateResult(server, "online", elapsed, null);
                        log.debug("TSA probe OK: {} ({}ms, status={})", server.getName(), elapsed, status);
                    } else {
                        String statusText = tsResponse.getStatusString() != null ? tsResponse.getStatusString() : "status=" + status;
                        updateResult(server, "error", elapsed, "TSP status: " + statusText);
                        log.debug("TSA probe error response: {} - {}", server.getName(), statusText);
                    }
                } catch (Exception parseEx) {
                    // Got HTTP 200 but couldn't parse TSP response — might be a web page
                    String contentType = response.headers().firstValue("Content-Type").orElse("");
                    if (contentType.contains("timestamp") || contentType.contains("octet-stream")) {
                        updateResult(server, "online", elapsed, "Response parsing issue: " + parseEx.getMessage());
                    } else {
                        updateResult(server, "error", elapsed, "Not a TSP endpoint (Content-Type: " + contentType + ")");
                    }
                }
            } else {
                updateResult(server, "error", System.currentTimeMillis() - startTime,
                        "HTTP " + response.statusCode());
            }
        } catch (java.net.http.HttpTimeoutException e) {
            updateResult(server, "offline", System.currentTimeMillis() - startTime, "Timeout");
            log.debug("TSA probe timeout: {}", server.getName());
        } catch (Exception e) {
            updateResult(server, "offline", System.currentTimeMillis() - startTime, e.getMessage());
            log.debug("TSA probe failed: {} - {}", server.getName(), e.getMessage());
        }
    }

    private void updateResult(TsaServer server, String status, long responseTimeMs, String error) {
        TsaServer result = probeResults.get(server.getId());
        if (result == null) {
            result = TsaServer.builder()
                    .id(server.getId())
                    .url(server.getUrl())
                    .name(server.getName())
                    .provider(server.getProvider())
                    .country(server.getCountry())
                    .category(server.getCategory())
                    .info(server.getInfo())
                    .qualifiedEidas(server.isQualifiedEidas())
                    .freeTier(server.isFreeTier())
                    .build();
            probeResults.put(server.getId(), result);
        }
        result.setStatus(status);
        result.setResponseTimeMs(responseTimeMs);
        result.setLastProbeError(error);
        result.setLastProbeAt(System.currentTimeMillis());
    }

    /**
     * Build a minimal RFC 3161 TimeStampRequest with SHA-256 hash of dummy data.
     */
    private byte[] buildTimestampRequest() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest("pdfalyzer-tsa-probe".getBytes());

        TimeStampRequestGenerator gen = new TimeStampRequestGenerator();
        gen.setCertReq(false); // don't request certificate in response (smaller)
        TimeStampRequest tsReq = gen.generate(NISTObjectIdentifiers.id_sha256, hash);
        return tsReq.getEncoded();
    }
}
