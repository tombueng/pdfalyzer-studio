package io.pdfalyzer.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.util.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import io.pdfalyzer.model.TrustListStatus;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages EUTL (EU Trusted List) and AATL (Adobe Approved Trust List) download, parsing, and caching.
 * Trust lists are cached globally (not per-session) and fetched on-demand based on certificate issuers.
 */
@Service
@Slf4j
public class TrustListService {


    @Value("${pdfalyzer.trust.eutl.lotl-url}")
    private String lotlUrl;

    @Value("${pdfalyzer.trust.aatl.url}")
    private String aatlUrl;

    @Value("${pdfalyzer.trust.eutl.cache-ttl-minutes}")
    private int eutlCacheTtlMinutes;

    @Value("${pdfalyzer.trust.aatl.cache-ttl-minutes}")
    private int aatlCacheTtlMinutes;

    @Value("${pdfalyzer.trust.download-timeout-ms}")
    private int downloadTimeoutMs;

    // Global caches
    private final ConcurrentHashMap<String, TslCacheEntry> tslCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, X509Certificate> eutlTrustAnchors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, X509Certificate> aatlTrustAnchors = new ConcurrentHashMap<>();
    private volatile LotlParseResult cachedLotl;
    private volatile Instant aatlLoadedAt;

    // Status tracking
    private volatile TrustListStatus eutlStatus = TrustListStatus.builder()
            .listType("EUTL").status("NOT_LOADED").build();
    private volatile TrustListStatus aatlStatus = TrustListStatus.builder()
            .listType("AATL").status("NOT_LOADED").build();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final HttpClient httpClient;

    public TrustListService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(15000))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Resolve which EU countries need to be fetched based on certificate chain issuer DNs.
     * Extracts C= attribute from each certificate's issuer DN.
     */
    public List<String> resolveCountriesForCertChain(List<X509Certificate> certs) {
        List<String> countries = new ArrayList<>();
        for (X509Certificate cert : certs) {
            String issuerDN = cert.getIssuerX500Principal().getName();
            String country = extractCountryCode(issuerDN);
            if (country != null && !countries.contains(country)) {
                countries.add(country);
            }
            // Also check subject DN (for self-issued intermediates)
            String subjectDN = cert.getSubjectX500Principal().getName();
            country = extractCountryCode(subjectDN);
            if (country != null && !countries.contains(country)) {
                countries.add(country);
            }
        }
        log.debug("Resolved {} countries from certificate chain: {}", countries.size(), countries);
        return countries;
    }

    /**
     * Fetch LOTL and then specific country TSLs asynchronously. Reports progress via callback.
     */
    public void fetchCountryTslsAsync(List<String> countryCodes, Consumer<TrustListStatus> progressCallback) {
        executor.submit(() -> {
            try {
                // Step 1: Fetch LOTL if not cached or expired
                if (cachedLotl == null || isExpired(cachedLotl.getFetchedAt(), eutlCacheTtlMinutes)) {
                    updateEutlStatus("LOADING", "Fetching EU List of Trusted Lists...", null, 0, countryCodes.size());
                    progressCallback.accept(eutlStatus);
                    fetchLotl();
                }

                if (cachedLotl == null) {
                    updateEutlStatus("ERROR", "Failed to fetch LOTL", null, 0, 0);
                    progressCallback.accept(eutlStatus);
                    return;
                }

                // Step 2: Fetch each needed country TSL
                int fetched = 0;
                for (String country : countryCodes) {
                    TslCacheEntry cached = tslCache.get(country);
                    if (cached != null && !isExpired(cached.getFetchedAt(), eutlCacheTtlMinutes)) {
                        fetched++;
                        continue; // already cached and fresh
                    }

                    String tslUrl = cachedLotl.getCountryTslUrls().get(country);
                    if (tslUrl == null) {
                        log.debug("No TSL URL found for country: {}", country);
                        fetched++;
                        continue;
                    }

                    String countryLabel = country + " (" + getCountryName(country) + ")";
                    updateEutlStatus("LOADING", "Fetching TSL for " + countryLabel + "...",
                            countryLabel, fetched, countryCodes.size());
                    progressCallback.accept(eutlStatus);

                    fetchCountryTsl(country, tslUrl);
                    fetched++;
                }

                // Final status
                List<String> loaded = new ArrayList<>(tslCache.keySet());
                eutlStatus = TrustListStatus.builder()
                        .listType("EUTL")
                        .status("LOADED")
                        .statusMessage("Loaded " + eutlTrustAnchors.size() + " trust anchors from " + loaded.size() + " countries")
                        .loadedCountries(loaded)
                        .totalTrustAnchors(eutlTrustAnchors.size())
                        .loadedAt(Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .fetchedCount(fetched)
                        .totalToFetch(countryCodes.size())
                        .build();
                progressCallback.accept(eutlStatus);

            } catch (Exception e) {
                log.error("Error fetching EUTL trust lists", e);
                updateEutlStatus("ERROR", "Error: " + e.getMessage(), null, 0, 0);
                progressCallback.accept(eutlStatus);
            }
        });
    }

    /**
     * Fetch AATL asynchronously.
     */
    public void fetchAatlAsync(Consumer<TrustListStatus> progressCallback) {
        executor.submit(() -> {
            try {
                if (aatlLoadedAt != null && !isExpired(aatlLoadedAt, aatlCacheTtlMinutes)) {
                    progressCallback.accept(aatlStatus);
                    return;
                }

                aatlStatus = TrustListStatus.builder()
                        .listType("AATL").status("LOADING")
                        .statusMessage("Fetching Adobe Approved Trust List...")
                        .build();
                progressCallback.accept(aatlStatus);

                fetchAatl();

                aatlStatus = TrustListStatus.builder()
                        .listType("AATL")
                        .status("LOADED")
                        .statusMessage("Loaded " + aatlTrustAnchors.size() + " trust anchors")
                        .totalTrustAnchors(aatlTrustAnchors.size())
                        .loadedAt(Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .build();
                progressCallback.accept(aatlStatus);

            } catch (Exception e) {
                log.error("Error fetching AATL", e);
                aatlStatus = TrustListStatus.builder()
                        .listType("AATL").status("ERROR")
                        .statusMessage("Error: " + e.getMessage())
                        .build();
                progressCallback.accept(aatlStatus);
            }
        });
    }

    /**
     * Check if a certificate is a trust anchor in any loaded trust list.
     */
    public TrustAnchorMatch findTrustAnchor(X509Certificate cert) {
        String subjectDN = cert.getSubjectX500Principal().getName();
        String serial = cert.getSerialNumber().toString(16);

        // Check EUTL
        for (Map.Entry<String, X509Certificate> entry : eutlTrustAnchors.entrySet()) {
            X509Certificate anchor = entry.getValue();
            if (anchor.getSubjectX500Principal().equals(cert.getSubjectX500Principal())
                    && anchor.getSerialNumber().equals(cert.getSerialNumber())) {
                // Find which country this came from
                String key = entry.getKey();
                String country = key.contains(":") ? key.substring(0, key.indexOf(':')) : "EU";
                return TrustAnchorMatch.builder()
                        .found(true)
                        .listSource("EUTL:" + country)
                        .serviceName(subjectDN)
                        .serviceStatus("granted")
                        .build();
            }
        }

        // Check AATL
        for (X509Certificate anchor : aatlTrustAnchors.values()) {
            if (anchor.getSubjectX500Principal().equals(cert.getSubjectX500Principal())
                    && anchor.getSerialNumber().equals(cert.getSerialNumber())) {
                return TrustAnchorMatch.builder()
                        .found(true)
                        .listSource("AATL")
                        .serviceName(subjectDN)
                        .serviceStatus("approved")
                        .build();
            }
        }

        return TrustAnchorMatch.builder().found(false).build();
    }

    /**
     * Fallback lookup by RFC-2253 subject DN + lowercase hex serial — used when
     * no raw X509Certificate object is available (cert not embedded in CMS store).
     */
    public TrustAnchorMatch findTrustAnchorByDn(String subjectDN, String serialHex) {
        for (Map.Entry<String, X509Certificate> entry : eutlTrustAnchors.entrySet()) {
            X509Certificate anchor = entry.getValue();
            if (anchor.getSerialNumber().toString(16).equalsIgnoreCase(serialHex)
                    && anchor.getSubjectX500Principal().getName().equals(subjectDN)) {
                String key = entry.getKey();
                String country = key.contains(":") ? key.substring(0, key.indexOf(':')) : "EU";
                return TrustAnchorMatch.builder()
                        .found(true).listSource("EUTL:" + country)
                        .serviceName(subjectDN).serviceStatus("granted").build();
            }
        }
        for (X509Certificate anchor : aatlTrustAnchors.values()) {
            if (anchor.getSerialNumber().toString(16).equalsIgnoreCase(serialHex)
                    && anchor.getSubjectX500Principal().getName().equals(subjectDN)) {
                return TrustAnchorMatch.builder()
                        .found(true).listSource("AATL")
                        .serviceName(subjectDN).serviceStatus("approved").build();
            }
        }
        return TrustAnchorMatch.builder().found(false).build();
    }

    /**
     * Resolve countries from CertificateChainEntry subject/issuer DNs — fallback when raw certs unavailable.
     */
    public List<String> resolveCountriesFromChainDns(List<String> subjectDns) {
        List<String> countries = new ArrayList<>();
        for (String dn : subjectDns) {
            String country = extractCountryCode(dn);
            if (country != null && !countries.contains(country)) {
                countries.add(country);
            }
        }
        log.debug("Resolved {} countries from chain DNs: {}", countries.size(), countries);
        return countries;
    }

    public TrustListStatus getEutlStatus() { return eutlStatus; }
    public TrustListStatus getAatlStatus() { return aatlStatus; }
    public boolean hasLoadedTrustAnchors() { return !eutlTrustAnchors.isEmpty() || !aatlTrustAnchors.isEmpty(); }

    // ── LOTL Parsing ────────────────────────────────────────────────────────────

    private void fetchLotl() {
        try {
            byte[] lotlBytes = downloadUrl(lotlUrl);
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            // Security: disable external entities
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(lotlBytes));

            Map<String, String> countryUrls = new ConcurrentHashMap<>();
            // LOTL contains OtherTSLPointer elements that point to national TSLs
            // Use "*" namespace wildcard — real LOTL may use v2#, v3#, or no-namespace variants
            NodeList pointers = doc.getElementsByTagNameNS("*", "OtherTSLPointer");
            for (int i = 0; i < pointers.getLength(); i++) {
                Element pointer = (Element) pointers.item(i);
                String tslLocation = getElementText(pointer, "TSLLocation");
                String territory = getElementText(pointer, "SchemeTerritory");
                if (tslLocation != null && territory != null) {
                    String path = tslLocation.contains("?")
                            ? tslLocation.substring(0, tslLocation.indexOf('?')) : tslLocation;
                    if (path.endsWith(".xml")) {
                        countryUrls.put(territory.toUpperCase(), tslLocation);
                    }
                }
            }

            cachedLotl = LotlParseResult.builder()
                    .countryTslUrls(countryUrls)
                    .fetchedAt(Instant.now())
                    .build();
            log.info("Parsed LOTL: {} country TSL URLs found", countryUrls.size());

        } catch (Exception e) {
            log.error("Failed to fetch/parse LOTL from {}: {}", lotlUrl, e.getMessage());
        }
    }

    // ── Country TSL Parsing ─────────────────────────────────────────────────────

    private void fetchCountryTsl(String country, String tslUrl) {
        try {
            byte[] tslBytes = downloadUrl(tslUrl);
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(tslBytes));

            List<TrustServiceEntry> services = new ArrayList<>();
            String nextUpdate = getElementText(doc.getDocumentElement(), "NextUpdate");

            // Extract TrustServiceProvider > TSPServices > TSPService entries
            NodeList serviceElements = doc.getElementsByTagNameNS("*", "TSPService");
            for (int i = 0; i < serviceElements.getLength(); i++) {
                Element serviceEl = (Element) serviceElements.item(i);
                String serviceName = getElementText(serviceEl, "Name");
                String serviceStatus = getElementText(serviceEl, "ServiceStatus");
                String serviceType = getElementText(serviceEl, "ServiceTypeIdentifier");

                // Only interested in CA/QC services
                if (serviceType != null && !serviceType.contains("CA/QC") && !serviceType.contains("CA_QC")
                        && !serviceType.contains("Certstatus") && !serviceType.contains("TSA")) {
                    // Still parse, might have certs we need
                }

                // Extract certificates from ServiceDigitalIdentity
                NodeList certElements = serviceEl.getElementsByTagNameNS("*", "X509Certificate");
                for (int j = 0; j < certElements.getLength(); j++) {
                    String b64 = certElements.item(j).getTextContent().replaceAll("\\s+", "");
                    if (b64.isEmpty()) continue;
                    try {
                        byte[] certBytes = Base64.getDecoder().decode(b64);
                        CertificateFactory cf = CertificateFactory.getInstance("X.509");
                        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                                new ByteArrayInputStream(certBytes));

                        String key = country + ":" + cert.getSerialNumber().toString(16);
                        eutlTrustAnchors.put(key, cert);

                        services.add(TrustServiceEntry.builder()
                                .serviceName(serviceName)
                                .serviceType(serviceType)
                                .serviceStatus(serviceStatus)
                                .certificate(cert)
                                .build());
                    } catch (Exception e) {
                        log.info("Failed to parse TSL certificate in {} (service: {}): {}", country, serviceName, e.getMessage());
                    }
                }
            }

            tslCache.put(country, TslCacheEntry.builder()
                    .countryCode(country)
                    .services(services)
                    .fetchedAt(Instant.now())
                    .nextUpdate(nextUpdate)
                    .build());

            log.info("Parsed TSL for {}: {} services, {} certificates", country, services.size(),
                    services.stream().filter(s -> s.getCertificate() != null).count());

        } catch (Exception e) {
            log.error("Failed to fetch/parse TSL for {}: {}", country, e.getMessage());
        }
    }

    // ── AATL Parsing ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void fetchAatl() {
        try {
            byte[] aatlBytes = downloadUrl(aatlUrl);

            // AATL is typically a CMS-signed file containing certificate data
            // Try to parse as CMS first
            try {
                CMSSignedData cmsData = new CMSSignedData(aatlBytes);
                Store<X509CertificateHolder> certStore = cmsData.getCertificates();
                Collection<X509CertificateHolder> allCerts = certStore.getMatches(null);
                JcaX509CertificateConverter converter = new JcaX509CertificateConverter();

                for (X509CertificateHolder holder : allCerts) {
                    X509Certificate cert = converter.getCertificate(holder);
                    String key = cert.getSerialNumber().toString(16);
                    aatlTrustAnchors.put(key, cert);
                }
                aatlLoadedAt = Instant.now();
                log.info("Parsed AATL (CMS): {} certificates", aatlTrustAnchors.size());
                return;
            } catch (Exception e) {
                log.debug("AATL is not CMS format, trying XML/FDF: {}", e.getMessage());
            }

            // Fallback: try parsing as XML (FDF/XFDF format)
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document doc = db.parse(new ByteArrayInputStream(aatlBytes));

                NodeList certElements = doc.getElementsByTagName("x509Cert");
                if (certElements.getLength() == 0) {
                    certElements = doc.getElementsByTagName("X509Certificate");
                }

                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                for (int i = 0; i < certElements.getLength(); i++) {
                    String b64 = certElements.item(i).getTextContent().trim();
                    try {
                        byte[] certBytes = Base64.getMimeDecoder().decode(b64);
                        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                                new ByteArrayInputStream(certBytes));
                        aatlTrustAnchors.put(cert.getSerialNumber().toString(16), cert);
                    } catch (Exception ex) {
                        log.debug("Failed to parse AATL certificate: {}", ex.getMessage());
                    }
                }
                aatlLoadedAt = Instant.now();
                log.info("Parsed AATL (XML): {} certificates", aatlTrustAnchors.size());
            } catch (Exception e) {
                log.error("Failed to parse AATL as XML: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("Failed to download AATL: {}", e.getMessage());
            throw new RuntimeException("AATL download failed", e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private byte[] downloadUrl(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(downloadTimeoutMs))
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, identity")
                .header("User-Agent", "PDFalyzer/1.0 (trust-list-client)")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + " from " + url);
        }
        byte[] body = response.body();
        // Decompress gzip if server returned Content-Encoding: gzip
        String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
        if ("gzip".equalsIgnoreCase(contentEncoding)) {
            try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = gz.readAllBytes();
            }
        }
        log.debug("Downloaded {} bytes from {} (encoding: {})", body.length, url, contentEncoding.isEmpty() ? "none" : contentEncoding);
        return body;
    }

    private String extractCountryCode(String dn) {
        if (dn == null) return null;
        // Match C= in X.500 DN (can be C=XX or C = XX or OID 2.5.4.6)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:C|2\\.5\\.4\\.6)\\s*=\\s*([A-Z]{2})",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(dn);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    private String getElementText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    private boolean isExpired(Instant loadedAt, int ttlMinutes) {
        return loadedAt == null || Duration.between(loadedAt, Instant.now()).toMinutes() >= ttlMinutes;
    }

    private void updateEutlStatus(String status, String message, String currentlyFetching,
                                   int fetchedCount, int totalToFetch) {
        eutlStatus = TrustListStatus.builder()
                .listType("EUTL")
                .status(status)
                .statusMessage(message)
                .currentlyFetching(currentlyFetching)
                .fetchedCount(fetchedCount)
                .totalToFetch(totalToFetch)
                .loadedCountries(new ArrayList<>(tslCache.keySet()))
                .totalTrustAnchors(eutlTrustAnchors.size())
                .build();
    }

    private String getCountryName(String code) {
        return switch (code) {
            case "AT" -> "Austria";
            case "BE" -> "Belgium";
            case "BG" -> "Bulgaria";
            case "HR" -> "Croatia";
            case "CY" -> "Cyprus";
            case "CZ" -> "Czech Republic";
            case "DK" -> "Denmark";
            case "EE" -> "Estonia";
            case "FI" -> "Finland";
            case "FR" -> "France";
            case "DE" -> "Germany";
            case "GR" -> "Greece";
            case "HU" -> "Hungary";
            case "IE" -> "Ireland";
            case "IT" -> "Italy";
            case "LV" -> "Latvia";
            case "LT" -> "Lithuania";
            case "LU" -> "Luxembourg";
            case "MT" -> "Malta";
            case "NL" -> "Netherlands";
            case "PL" -> "Poland";
            case "PT" -> "Portugal";
            case "RO" -> "Romania";
            case "SK" -> "Slovakia";
            case "SI" -> "Slovenia";
            case "ES" -> "Spain";
            case "SE" -> "Sweden";
            case "NO" -> "Norway";
            case "IS" -> "Iceland";
            case "LI" -> "Liechtenstein";
            case "CH" -> "Switzerland";
            case "UK" -> "United Kingdom";
            default -> code;
        };
    }

    @Data
    @Builder
    public static class LotlParseResult {
        private Map<String, String> countryTslUrls;
        private Instant fetchedAt;
    }

    @Data
    @Builder
    public static class TslCacheEntry {
        private String countryCode;
        private List<TrustServiceEntry> services;
        private Instant fetchedAt;
        private String nextUpdate;
    }

    @Data
    @Builder
    public static class TrustServiceEntry {
        private String serviceName;
        private String serviceType;
        private String serviceStatus;
        private X509Certificate certificate;
    }

    @Data
    @Builder
    public static class TrustAnchorMatch {
        private boolean found;
        private String listSource;
        private String serviceName;
        private String serviceStatus;
    }
}
