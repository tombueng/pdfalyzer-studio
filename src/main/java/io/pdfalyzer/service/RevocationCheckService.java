package io.pdfalyzer.service;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.RevocationStatus;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Performs OCSP and CRL revocation checking for certificates.
 * Also extracts embedded OCSP/CRL responses from PDF DSS (Document Security Store).
 */
@Service
@Slf4j
public class RevocationCheckService {

    @Value("${pdfalyzer.trust.ocsp-timeout-ms}")
    private int ocspTimeoutMs;

    @Value("${pdfalyzer.trust.crl-timeout-ms}")
    private int crlTimeoutMs;

    @Value("${pdfalyzer.trust.enable-online-revocation}")
    private boolean enableOnlineRevocation;

    private final ConcurrentHashMap<String, CrlCacheEntry> crlCache = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    public RevocationCheckService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(10000))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Check revocation status for a certificate.
     * Tries OCSP first (preferred), then falls back to CRL.
     */
    public RevocationStatus checkRevocation(X509Certificate cert, X509Certificate issuer,
                                            List<String> ocspUrls, List<String> crlUrls) {
        if (!enableOnlineRevocation) {
            return RevocationStatus.builder()
                    .status("NOT_CHECKED")
                    .errorMessage("Online revocation checking is disabled")
                    .build();
        }

        // Try OCSP first
        for (String ocspUrl : ocspUrls) {
            try {
                RevocationStatus status = checkOcsp(cert, issuer, ocspUrl);
                if (status != null && !"ERROR".equals(status.getStatus())) {
                    return status;
                }
            } catch (Exception e) {
                log.debug("OCSP check failed for {}: {}", ocspUrl, e.getMessage());
            }
        }

        // Fall back to CRL
        for (String crlUrl : crlUrls) {
            try {
                RevocationStatus status = checkCrl(cert, crlUrl);
                if (status != null && !"ERROR".equals(status.getStatus())) {
                    return status;
                }
            } catch (Exception e) {
                log.debug("CRL check failed for {}: {}", crlUrl, e.getMessage());
            }
        }

        // No check succeeded
        if (ocspUrls.isEmpty() && crlUrls.isEmpty()) {
            return RevocationStatus.builder()
                    .status("UNKNOWN")
                    .errorMessage("No OCSP or CRL URLs available in certificate")
                    .build();
        }

        return RevocationStatus.builder()
                .status("ERROR")
                .errorMessage("All revocation checks failed (tried " +
                        ocspUrls.size() + " OCSP, " + crlUrls.size() + " CRL)")
                .build();
    }

    /**
     * Check revocation using DSS-embedded data first, then online.
     */
    public RevocationStatus checkWithDssFallback(X509Certificate cert, X509Certificate issuer,
                                                  List<String> ocspUrls, List<String> crlUrls,
                                                  DssContents dss) {
        if (dss != null && dss.isHasDss()) {
            // Try embedded OCSP responses
            for (byte[] ocspBytes : dss.getEmbeddedOcspResponses()) {
                try {
                    RevocationStatus status = parseOcspResponse(ocspBytes, cert);
                    if (status != null && !"ERROR".equals(status.getStatus())) {
                        status.setCheckedVia("DSS_OCSP");
                        return status;
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse embedded OCSP response: {}", e.getMessage());
                }
            }

            // Try embedded CRLs
            for (byte[] crlBytes : dss.getEmbeddedCrls()) {
                try {
                    RevocationStatus status = parseCrlBytes(cert, crlBytes);
                    if (status != null && !"ERROR".equals(status.getStatus())) {
                        status.setCheckedVia("DSS_CRL");
                        return status;
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse embedded CRL: {}", e.getMessage());
                }
            }
        }

        // Fall back to online checking
        return checkRevocation(cert, issuer, ocspUrls, crlUrls);
    }

    /**
     * Extract DSS (Document Security Store) contents from a PDF.
     */
    public DssContents extractDssFromPdf(byte[] pdfBytes) {
        List<byte[]> ocspResponses = new ArrayList<>();
        List<byte[]> crls = new ArrayList<>();
        List<X509Certificate> dssCerts = new ArrayList<>();
        boolean hasDss = false;

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
            COSBase dssBase = catalog.getDictionaryObject(COSName.getPDFName("DSS"));
            if (!(dssBase instanceof COSDictionary dssDictionary)) {
                return DssContents.builder().hasDss(false).build();
            }

            hasDss = true;
            log.debug("Found DSS dictionary in PDF");

            // Extract OCSPs
            COSBase ocspsBase = dssDictionary.getDictionaryObject(COSName.getPDFName("OCSPs"));
            if (ocspsBase instanceof COSArray ocspsArray) {
                for (int i = 0; i < ocspsArray.size(); i++) {
                    COSBase item = ocspsArray.getObject(i);
                    if (item instanceof COSStream stream) {
                        byte[] data = stream.createInputStream().readAllBytes();
                        ocspResponses.add(data);
                    }
                }
                log.debug("Extracted {} OCSP responses from DSS", ocspResponses.size());
            }

            // Extract CRLs
            COSBase crlsBase = dssDictionary.getDictionaryObject(COSName.getPDFName("CRLs"));
            if (crlsBase instanceof COSArray crlsArray) {
                for (int i = 0; i < crlsArray.size(); i++) {
                    COSBase item = crlsArray.getObject(i);
                    if (item instanceof COSStream stream) {
                        byte[] data = stream.createInputStream().readAllBytes();
                        crls.add(data);
                    }
                }
                log.debug("Extracted {} CRLs from DSS", crls.size());
            }

            // Extract Certs
            COSBase certsBase = dssDictionary.getDictionaryObject(COSName.getPDFName("Certs"));
            if (certsBase instanceof COSArray certsArray) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                for (int i = 0; i < certsArray.size(); i++) {
                    COSBase item = certsArray.getObject(i);
                    if (item instanceof COSStream stream) {
                        byte[] data = stream.createInputStream().readAllBytes();
                        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                                new ByteArrayInputStream(data));
                        dssCerts.add(cert);
                    }
                }
                log.debug("Extracted {} certificates from DSS", dssCerts.size());
            }

        } catch (Exception e) {
            log.debug("Error extracting DSS from PDF: {}", e.getMessage());
        }

        return DssContents.builder()
                .hasDss(hasDss)
                .embeddedOcspResponses(ocspResponses)
                .embeddedCrls(crls)
                .dssCerts(dssCerts)
                .build();
    }

    // ── OCSP ────────────────────────────────────────────────────────────────────

    private RevocationStatus checkOcsp(X509Certificate cert, X509Certificate issuer, String ocspUrl) {
        try {
            DigestCalculatorProvider dcp = new JcaDigestCalculatorProviderBuilder().build();
            DigestCalculator digestCalc = dcp.get(new AlgorithmIdentifier(
                    org.bouncycastle.asn1.oiw.OIWObjectIdentifiers.idSHA1));

            X509CertificateHolder issuerHolder = new JcaX509CertificateHolder(issuer);
            CertificateID certId = new CertificateID(digestCalc, issuerHolder, cert.getSerialNumber());

            OCSPReqBuilder reqBuilder = new OCSPReqBuilder();
            reqBuilder.addRequest(certId);

            // Add nonce extension for replay protection
            BigInteger nonce = BigInteger.valueOf(System.currentTimeMillis());
            Extensions exts = new Extensions(new Extension(
                    OCSPObjectIdentifiers.id_pkix_ocsp_nonce, false,
                    new DEROctetString(nonce.toByteArray())));
            reqBuilder.setRequestExtensions(exts);

            OCSPReq ocspReq = reqBuilder.build();
            byte[] reqBytes = ocspReq.getEncoded();

            // Send OCSP request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ocspUrl))
                    .timeout(Duration.ofMillis(ocspTimeoutMs))
                    .header("Content-Type", "application/ocsp-request")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(reqBytes))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return RevocationStatus.builder()
                        .status("ERROR")
                        .checkedVia("OCSP")
                        .ocspResponderUrl(ocspUrl)
                        .errorMessage("HTTP " + response.statusCode())
                        .build();
            }

            return parseOcspResponse(response.body(), cert);

        } catch (Exception e) {
            log.debug("OCSP request failed for {}: {}", ocspUrl, e.getMessage());
            return RevocationStatus.builder()
                    .status("ERROR")
                    .checkedVia("OCSP")
                    .ocspResponderUrl(ocspUrl)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private RevocationStatus parseOcspResponse(byte[] responseBytes, X509Certificate cert) throws Exception {
        OCSPResp ocspResp = new OCSPResp(responseBytes);
        if (ocspResp.getStatus() != OCSPResp.SUCCESSFUL) {
            return RevocationStatus.builder()
                    .status("ERROR")
                    .checkedVia("OCSP")
                    .errorMessage("OCSP response status: " + ocspResp.getStatus())
                    .build();
        }

        BasicOCSPResp basicResp = (BasicOCSPResp) ocspResp.getResponseObject();
        String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        for (SingleResp singleResp : basicResp.getResponses()) {
            // Check if this response is for our certificate
            if (singleResp.getCertID().getSerialNumber().equals(cert.getSerialNumber())) {
                Object certStatus = singleResp.getCertStatus();
                if (certStatus == null) {
                    // null means GOOD in OCSP
                    return RevocationStatus.builder()
                            .status("GOOD")
                            .checkedVia("OCSP")
                            .checkedAt(now)
                            .build();
                }
                if (certStatus instanceof RevokedStatus revoked) {
                    return RevocationStatus.builder()
                            .status("REVOKED")
                            .checkedVia("OCSP")
                            .checkedAt(now)
                            .revokedAt(revoked.getRevocationTime().toInstant()
                                    .atOffset(ZoneOffset.UTC)
                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                            .revocationReason(revoked.hasRevocationReason()
                                    ? String.valueOf(revoked.getRevocationReason()) : null)
                            .build();
                }
                // Unknown status
                return RevocationStatus.builder()
                        .status("UNKNOWN")
                        .checkedVia("OCSP")
                        .checkedAt(now)
                        .build();
            }
        }

        return null; // No matching response found
    }

    // ── CRL ─────────────────────────────────────────────────────────────────────

    private RevocationStatus checkCrl(X509Certificate cert, String crlUrl) {
        try {
            // Check cache first
            CrlCacheEntry cached = crlCache.get(crlUrl);
            if (cached != null && cached.getNextUpdate() != null
                    && Instant.now().isBefore(cached.getNextUpdate())) {
                return parseCrlBytes(cert, cached.getCrlBytes());
            }

            // Download CRL
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(crlUrl))
                    .timeout(Duration.ofMillis(crlTimeoutMs))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return RevocationStatus.builder()
                        .status("ERROR")
                        .checkedVia("CRL")
                        .crlUrl(crlUrl)
                        .errorMessage("HTTP " + response.statusCode())
                        .build();
            }

            byte[] crlBytes = response.body();
            RevocationStatus status = parseCrlBytes(cert, crlBytes);

            // Cache the CRL
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(new ByteArrayInputStream(crlBytes));
            Instant nextUpdate = crl.getNextUpdate() != null ? crl.getNextUpdate().toInstant() : null;
            crlCache.put(crlUrl, CrlCacheEntry.builder()
                    .crlBytes(crlBytes)
                    .fetchedAt(Instant.now())
                    .nextUpdate(nextUpdate)
                    .build());

            if (status != null) {
                status.setCrlUrl(crlUrl);
            }
            return status;

        } catch (Exception e) {
            log.debug("CRL check failed for {}: {}", crlUrl, e.getMessage());
            return RevocationStatus.builder()
                    .status("ERROR")
                    .checkedVia("CRL")
                    .crlUrl(crlUrl)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private RevocationStatus parseCrlBytes(X509Certificate cert, byte[] crlBytes) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509CRL crl = (X509CRL) cf.generateCRL(new ByteArrayInputStream(crlBytes));
        String now = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        if (crl.isRevoked(cert)) {
            java.security.cert.X509CRLEntry entry = crl.getRevokedCertificate(cert.getSerialNumber());
            return RevocationStatus.builder()
                    .status("REVOKED")
                    .checkedVia("CRL")
                    .checkedAt(now)
                    .revokedAt(entry != null && entry.getRevocationDate() != null
                            ? entry.getRevocationDate().toInstant()
                                    .atOffset(ZoneOffset.UTC)
                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null)
                    .revocationReason(entry != null && entry.getRevocationReason() != null
                            ? entry.getRevocationReason().name() : null)
                    .build();
        }

        return RevocationStatus.builder()
                .status("GOOD")
                .checkedVia("CRL")
                .checkedAt(now)
                .build();
    }

    @Data
    @Builder
    public static class DssContents {
        private boolean hasDss;
        @Builder.Default
        private List<byte[]> embeddedOcspResponses = new ArrayList<>();
        @Builder.Default
        private List<byte[]> embeddedCrls = new ArrayList<>();
        @Builder.Default
        private List<X509Certificate> dssCerts = new ArrayList<>();
    }

    @Data
    @Builder
    private static class CrlCacheEntry {
        private byte[] crlBytes;
        private Instant fetchedAt;
        private Instant nextUpdate;
    }
}
