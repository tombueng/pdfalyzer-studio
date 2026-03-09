package io.pdfalyzer.service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.pdfalyzer.model.CscCredential;
import io.pdfalyzer.model.CscProvider;
import io.pdfalyzer.model.CscSignResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CSC (Cloud Signature Consortium) API client.
 * Implements the core CSC v1/v2 endpoints for remote signing:
 * auth/login, credentials/list, credentials/info, credentials/authorize,
 * credentials/sendOTP, signatures/signHash.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CscApiClient {

    private final ObjectMapper objectMapper;

    @Value("${pdfalyzer.csc.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${pdfalyzer.csc.read-timeout-ms:30000}")
    private int readTimeoutMs;

    // Per-session token storage: sessionId -> { providerId -> accessToken }
    private final Map<String, Map<String, String>> sessionTokens = new ConcurrentHashMap<>();

    // ── Authentication ─────────────────────────────────────────────────────

    /**
     * Authenticate with a CSC provider using Resource Owner Password Grant
     * (auth/login endpoint). Returns the access token.
     */
    public String authenticate(CscProvider provider, String username, String password) {
        String loginUrl = provider.getBaseUrl() + "/auth/login";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("remoteUser", username);
        body.put("remoteSecret", password);

        JsonNode response = postJson(loginUrl, body, null);
        String token = textOrNull(response, "access_token");
        if (token == null) {
            throw new IllegalStateException("CSC auth/login did not return an access_token");
        }
        log.info("CSC authentication successful with provider: {}", provider.getName());
        return token;
    }

    /**
     * Authenticate using OAuth2 Client Credentials Grant (token endpoint).
     */
    public String authenticateClientCredentials(CscProvider provider) {
        String tokenUrl = provider.getTokenUrl();
        if (tokenUrl == null || tokenUrl.isBlank()) {
            tokenUrl = provider.getBaseUrl() + "/oauth2/token";
        }

        String formBody = "grant_type=client_credentials"
                + "&client_id=" + urlEncode(provider.getClientId())
                + "&client_secret=" + urlEncode(provider.getClientSecret());

        JsonNode response = postForm(tokenUrl, formBody);
        String token = textOrNull(response, "access_token");
        if (token == null) {
            throw new IllegalStateException("OAuth2 token endpoint did not return an access_token");
        }
        log.info("CSC OAuth2 client_credentials auth successful with provider: {}", provider.getName());
        return token;
    }

    /**
     * Store an access token for a session + provider combination.
     */
    public void storeToken(String sessionId, String providerId, String accessToken) {
        sessionTokens.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(providerId, accessToken);
    }

    public String getToken(String sessionId, String providerId) {
        var tokens = sessionTokens.get(sessionId);
        return tokens != null ? tokens.get(providerId) : null;
    }

    public void clearTokens(String sessionId) {
        sessionTokens.remove(sessionId);
    }

    // ── Service Info ───────────────────────────────────────────────────────

    /**
     * GET /info — discover service capabilities. Does not require auth.
     */
    public JsonNode getServiceInfo(CscProvider provider) {
        String url = provider.getBaseUrl() + "/info";
        ObjectNode body = objectMapper.createObjectNode();
        return postJson(url, body, null);
    }

    // ── Credential Discovery ──────────────────────────────────────────────

    /**
     * POST /credentials/list — list available signing credentials.
     */
    public List<CscCredential> listCredentials(CscProvider provider, String accessToken) {
        String url = provider.getBaseUrl() + "/credentials/list";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("maxResults", 100);

        JsonNode response = postJson(url, body, accessToken);
        return parseCredentialsList(response, provider, accessToken);
    }

    /**
     * POST /credentials/info — get details for a specific credential.
     */
    public CscCredential getCredentialInfo(CscProvider provider, String accessToken, String credentialId) {
        String url = provider.getBaseUrl() + "/credentials/info";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("credentialID", credentialId);
        body.put("certificates", "chain");
        body.put("certInfo", true);
        body.put("authInfo", true);

        JsonNode response = postJson(url, body, accessToken);
        return parseSingleCredential(credentialId, response);
    }

    // ── Credential Authorization (SCAL2) ──────────────────────────────────

    /**
     * POST /credentials/sendOTP — trigger OTP delivery to the user.
     */
    public void sendOtp(CscProvider provider, String accessToken, String credentialId) {
        String url = provider.getBaseUrl() + "/credentials/sendOTP";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("credentialID", credentialId);

        postJson(url, body, accessToken);
        log.info("CSC OTP sent for credential: {}", credentialId);
    }

    /**
     * POST /credentials/authorize — authorize credential, get SAD token.
     * For SCAL2 credentials, include the hash(es) and OTP.
     */
    public String authorizeCredential(CscProvider provider, String accessToken,
                                       String credentialId, int numSignatures,
                                       List<String> hashesBase64, String otp) {
        String url = provider.getBaseUrl() + "/credentials/authorize";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("credentialID", credentialId);
        body.put("numSignatures", numSignatures);

        if (hashesBase64 != null && !hashesBase64.isEmpty()) {
            ArrayNode hashArray = body.putArray("hash");
            for (String h : hashesBase64) {
                hashArray.add(h);
            }
            body.put("hashAlgo", "2.16.840.1.101.3.4.2.1"); // SHA-256 OID
        }

        if (otp != null && !otp.isBlank()) {
            body.put("OTP", otp);
        }

        JsonNode response = postJson(url, body, accessToken);
        String sad = textOrNull(response, "SAD");
        if (sad == null) {
            throw new IllegalStateException("CSC credentials/authorize did not return a SAD token");
        }
        log.info("CSC credential authorized, SAD obtained for: {}", credentialId);
        return sad;
    }

    // ── Hash Signing ──────────────────────────────────────────────────────

    /**
     * POST /signatures/signHash — sign one or more hashes.
     * Returns base64-encoded raw signature values.
     */
    public CscSignResult signHash(CscProvider provider, String accessToken,
                                   String credentialId, String sad,
                                   List<String> hashesBase64, String signAlgoOid) {
        String url = provider.getBaseUrl() + "/signatures/signHash";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("credentialID", credentialId);
        body.put("SAD", sad);

        ArrayNode hashArray = body.putArray("hash");
        for (String h : hashesBase64) {
            hashArray.add(h);
        }

        body.put("hashAlgo", "2.16.840.1.101.3.4.2.1"); // SHA-256
        if (signAlgoOid != null) {
            body.put("signAlgo", signAlgoOid);
        } else {
            body.put("signAlgo", "1.2.840.113549.1.1.11"); // SHA256withRSA default
        }

        JsonNode response = postJson(url, body, accessToken);
        List<String> signatures = new ArrayList<>();
        JsonNode sigsNode = response.get("signatures");
        if (sigsNode != null && sigsNode.isArray()) {
            for (JsonNode sig : sigsNode) {
                signatures.add(sig.asText());
            }
        }

        log.info("CSC signHash returned {} signature(s)", signatures.size());
        return CscSignResult.builder().signatures(signatures).build();
    }

    // ── Certificate Parsing ───────────────────────────────────────────────

    /**
     * Parse an X.509 certificate from a Base64-encoded DER string.
     */
    public X509Certificate parseCertificate(String base64Cert) {
        try {
            byte[] certBytes = Base64.getDecoder().decode(base64Cert);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
        } catch (Exception e) {
            log.warn("Failed to parse CSC certificate: {}", e.getMessage());
            return null;
        }
    }

    // ── HTTP Helpers ──────────────────────────────────────────────────────

    private JsonNode postJson(String url, ObjectNode body, String accessToken) {
        try {
            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

            if (accessToken != null) {
                reqBuilder.header("Authorization", "Bearer " + accessToken);
            }

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .build();

            HttpResponse<String> response = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = response.body();
                log.error("CSC API error: {} {} -> {} {}", "POST", url, response.statusCode(), errorBody);
                throw new IllegalStateException("CSC API error " + response.statusCode() + ": " + errorBody);
            }

            return objectMapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("CSC API call failed: " + e.getMessage(), e);
        }
    }

    private JsonNode postForm(String url, String formBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OAuth2 token request failed: HTTP " + response.statusCode());
            }

            return objectMapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OAuth2 token request failed: " + e.getMessage(), e);
        }
    }

    // ── Response Parsing ──────────────────────────────────────────────────

    private List<CscCredential> parseCredentialsList(JsonNode response, CscProvider provider, String accessToken) {
        List<CscCredential> result = new ArrayList<>();

        JsonNode credIds = response.get("credentialIDs");
        if (credIds == null || !credIds.isArray()) {
            return result;
        }

        // Some providers return credentialInfos inline, others require separate info calls
        JsonNode credInfos = response.get("credentialInfos");

        for (int i = 0; i < credIds.size(); i++) {
            String credId = credIds.get(i).asText();
            if (credInfos != null && credInfos.isArray() && i < credInfos.size()) {
                result.add(parseSingleCredential(credId, credInfos.get(i)));
            } else {
                // Fetch info separately
                try {
                    result.add(getCredentialInfo(provider, accessToken, credId));
                } catch (Exception e) {
                    log.warn("Failed to get credential info for {}: {}", credId, e.getMessage());
                    result.add(CscCredential.builder()
                            .credentialId(credId)
                            .description("(info unavailable)")
                            .build());
                }
            }
        }

        return result;
    }

    private CscCredential parseSingleCredential(String credentialId, JsonNode info) {
        CscCredential.CscCredentialBuilder builder = CscCredential.builder()
                .credentialId(credentialId);

        if (info == null) return builder.build();

        builder.description(textOrNull(info, "description"));
        builder.scal(textOrNull(info, "SCAL"));
        builder.status(textOrNull(info, "status"));
        builder.multisign(info.has("multisign") && info.get("multisign").asBoolean());

        // Key info
        JsonNode keyNode = info.get("key");
        if (keyNode != null) {
            builder.keyLength(keyNode.has("len") ? keyNode.get("len").asInt() : 0);
            if (keyNode.has("algo") && keyNode.get("algo").isArray() && keyNode.get("algo").size() > 0) {
                List<String> algos = new ArrayList<>();
                for (JsonNode a : keyNode.get("algo")) {
                    algos.add(a.asText());
                }
                builder.supportedAlgos(algos);
                builder.keyAlgorithm(algos.get(0));
            }
        }

        // Certificate info
        JsonNode certNode = info.get("cert");
        if (certNode != null) {
            // Parse certificate chain
            JsonNode certsArray = certNode.get("certificates");
            if (certsArray != null && certsArray.isArray()) {
                List<String> certs = new ArrayList<>();
                for (JsonNode c : certsArray) {
                    certs.add(c.asText());
                }
                builder.certificates(certs);

                // Parse first cert for subject/issuer
                if (!certs.isEmpty()) {
                    X509Certificate x509 = parseCertificate(certs.get(0));
                    if (x509 != null) {
                        builder.subjectDN(x509.getSubjectX500Principal().getName());
                        builder.issuerDN(x509.getIssuerX500Principal().getName());
                        builder.serialNumber(x509.getSerialNumber().toString(16));
                        builder.validFrom(x509.getNotBefore().toInstant().toString());
                        builder.validUntil(x509.getNotAfter().toInstant().toString());
                    }
                }
            }

            // Some providers put subject/issuer in certInfo
            if (certNode.has("subjectDN")) {
                builder.subjectDN(certNode.get("subjectDN").asText());
            }
            if (certNode.has("issuerDN")) {
                builder.issuerDN(certNode.get("issuerDN").asText());
            }
        }

        return builder.build();
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    private String urlEncode(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
