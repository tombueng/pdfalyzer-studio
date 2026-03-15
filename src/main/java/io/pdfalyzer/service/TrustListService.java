package io.pdfalyzer.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
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
import java.util.zip.GZIPInputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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
        // Use a TLS-permissive SSLContext for trust list downloads.
        // TSL integrity is guaranteed by XML digital signatures (not TLS),
        // and some EU government TSL servers use certs not in Java's cacerts.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(15000))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .sslContext(createPermissiveSslContext())
                .build();
    }

    private static SSLContext createPermissiveSslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create permissive SSLContext for trust list downloads", e);
        }
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
     * Fetch LOTL and then country TSLs asynchronously. Reports progress via callback.
     * If any resolved country is not present in the LOTL (e.g. BM for QuoVadis),
     * automatically expands to fetch ALL LOTL countries, since the trust anchor
     * could be registered in any EU member state's TSL.
     */
    public void fetchCountryTslsAsync(List<String> resolvedCountries, Consumer<TrustListStatus> progressCallback) {
        executor.submit(() -> {
            try {
                // Step 1: Fetch LOTL if not cached or expired
                if (cachedLotl == null || isExpired(cachedLotl.getFetchedAt(), eutlCacheTtlMinutes)) {
                    updateEutlStatus("LOADING", "Fetching EU List of Trusted Lists...", null, 0, 0);
                    progressCallback.accept(eutlStatus);
                    fetchLotl();
                }

                if (cachedLotl == null) {
                    updateEutlStatus("ERROR", "Failed to fetch LOTL", null, 0, 0);
                    progressCallback.accept(eutlStatus);
                    return;
                }

                // Step 2: Determine which countries to fetch.
                // If any resolved country is NOT in the LOTL (e.g. BM/Bermuda for QuoVadis),
                // we must fetch all LOTL countries — the trust anchor could be in any of them.
                List<String> lotlCountries = new ArrayList<>(cachedLotl.getCountryTslUrls().keySet());
                List<String> countryCodes;
                List<String> nonEuCountries = new ArrayList<>();

                for (String c : resolvedCountries) {
                    if (!lotlCountries.contains(c)) {
                        nonEuCountries.add(c);
                    }
                }

                if (!nonEuCountries.isEmpty()) {
                    log.info("Cert chain contains non-EUTL countries {} — expanding to all {} LOTL countries",
                            nonEuCountries, lotlCountries.size());
                    countryCodes = new ArrayList<>(lotlCountries);
                } else {
                    countryCodes = new ArrayList<>(resolvedCountries);
                }
                java.util.Collections.sort(countryCodes);
                log.info("Will fetch {} EUTL country TSLs: {} (resolved: {}, non-EU: {})",
                        countryCodes.size(), countryCodes, resolvedCountries, nonEuCountries);

                // Step 3: Fetch each country TSL
                List<String> failed = new ArrayList<>();
                List<String> skipped = new ArrayList<>();
                int fetched = 0;

                for (String country : countryCodes) {
                    TslCacheEntry cached = tslCache.get(country);
                    if (cached != null && !isExpired(cached.getFetchedAt(), eutlCacheTtlMinutes)) {
                        log.debug("TSL for {} already cached ({} services)", country,
                                cached.getServices().size());
                        fetched++;
                        continue;
                    }

                    String tslUrl = cachedLotl.getCountryTslUrls().get(country);
                    if (tslUrl == null) {
                        log.debug("No TSL URL found for country: {}", country);
                        skipped.add(country);
                        fetched++;
                        continue;
                    }

                    String countryLabel = country + " (" + getCountryName(country) + ")";
                    updateEutlStatus("LOADING", "Fetching TSL: " + countryLabel,
                            countryLabel, fetched, countryCodes.size());
                    progressCallback.accept(eutlStatus);

                    boolean success = fetchCountryTsl(country, tslUrl);
                    if (!success) {
                        failed.add(country);
                        log.warn("Failed to fetch TSL for {} — continuing with remaining countries", country);
                    }
                    fetched++;
                }

                // Final status — use PARTIAL if any countries failed, LOADED only if all succeeded
                List<String> loaded = new ArrayList<>(tslCache.keySet());
                java.util.Collections.sort(loaded);
                log.info("EUTL loading complete: {} anchors from {} countries (loaded: {}, failed: {}, skipped: {})",
                        eutlTrustAnchors.size(), loaded.size(), loaded, failed, skipped);

                boolean hasFailed = !failed.isEmpty();
                String finalStatus = hasFailed ? "PARTIAL" : "LOADED";
                String msg = "Loaded " + eutlTrustAnchors.size() + " trust anchors from " + loaded.size() + " countries";
                if (hasFailed) {
                    msg += " (" + failed.size() + " countries failed: " + failed + ")";
                }

                eutlStatus = TrustListStatus.builder()
                        .listType("EUTL")
                        .status(finalStatus)
                        .statusMessage(msg)
                        .loadedCountries(loaded)
                        .failedCountries(failed)
                        .skippedCountries(skipped)
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
     * Uses multi-strategy matching:
     *   1. Exact match: subject DN + serial number
     *   2. Public key match: same key regardless of cert encoding/re-issuance
     * This handles cases where TSL cert encodings differ from CMS certs
     * (e.g. OID 2.5.4.97 organizationIdentifier encoding differences)
     * and where a CA cert was re-issued with the same key but different serial.
     */
    public TrustAnchorMatch findTrustAnchor(X509Certificate cert) {
        String subjectDN = cert.getSubjectX500Principal().getName();
        String serial = cert.getSerialNumber().toString(16);
        byte[] targetKeyEncoded = cert.getPublicKey().getEncoded();
        log.debug("Looking for trust anchor: serial={}, subject={}", serial, subjectDN);

        // Strategy 1: Exact match (subject + serial) — fastest, most precise
        TrustAnchorMatch exact = findInEutl(
                a -> a.getSubjectX500Principal().equals(cert.getSubjectX500Principal())
                        && a.getSerialNumber().equals(cert.getSerialNumber()),
                "exact");
        if (exact != null) {
            log.info("Trust anchor EXACT match in {} — serial={}, subject={}", exact.getListSource(), serial, subjectDN);
            return exact;
        }
        TrustAnchorMatch exactAatl = findInAatl(
                a -> a.getSubjectX500Principal().equals(cert.getSubjectX500Principal())
                        && a.getSerialNumber().equals(cert.getSerialNumber()));
        if (exactAatl != null) {
            log.info("Trust anchor EXACT match in AATL — serial={}, subject={}", serial, subjectDN);
            return exactAatl;
        }

        // Strategy 2: Public key match — catches re-issued certs and encoding differences
        TrustAnchorMatch keyMatch = findInEutl(
                a -> java.util.Arrays.equals(a.getPublicKey().getEncoded(), targetKeyEncoded),
                "pubkey");
        if (keyMatch != null) {
            log.info("Trust anchor PUBKEY match in {} — serial={}, subject={}", keyMatch.getListSource(), serial, subjectDN);
            return keyMatch;
        }
        TrustAnchorMatch keyMatchAatl = findInAatl(
                a -> java.util.Arrays.equals(a.getPublicKey().getEncoded(), targetKeyEncoded));
        if (keyMatchAatl != null) {
            log.info("Trust anchor PUBKEY match in AATL — serial={}, subject={}", serial, subjectDN);
            return keyMatchAatl;
        }

        log.info("No trust anchor found for: serial={}, subject={} (searched {} EUTL, {} AATL anchors)",
                serial, subjectDN, eutlTrustAnchors.size(), aatlTrustAnchors.size());
        return TrustAnchorMatch.builder().found(false).build();
    }

    /**
     * Fallback lookup by RFC-2253 subject DN + lowercase hex serial — used when
     * no raw X509Certificate object is available (cert not embedded in CMS store).
     * Also tries CN-based matching as a last resort for OID encoding differences.
     */
    public TrustAnchorMatch findTrustAnchorByDn(String subjectDN, String serialHex) {
        log.debug("Looking for trust anchor by DN: serial={}, subject={}", serialHex, subjectDN);

        // Strategy 1: Exact DN + serial string match
        TrustAnchorMatch exact = findInEutl(
                a -> a.getSerialNumber().toString(16).equalsIgnoreCase(serialHex)
                        && a.getSubjectX500Principal().getName().equals(subjectDN),
                "dn-exact");
        if (exact != null) {
            log.info("Trust anchor DN EXACT match in {} — serial={}", exact.getListSource(), serialHex);
            return exact;
        }
        TrustAnchorMatch exactAatl = findInAatl(
                a -> a.getSerialNumber().toString(16).equalsIgnoreCase(serialHex)
                        && a.getSubjectX500Principal().getName().equals(subjectDN));
        if (exactAatl != null) {
            log.info("Trust anchor DN EXACT match in AATL — serial={}", serialHex);
            return exactAatl;
        }

        // Strategy 2: Match by serial + CN substring (handles OID encoding differences like 2.5.4.97)
        String targetCN = extractCN(subjectDN);
        if (targetCN != null) {
            TrustAnchorMatch cnMatch = findInEutl(
                    a -> a.getSerialNumber().toString(16).equalsIgnoreCase(serialHex)
                            && targetCN.equals(extractCN(a.getSubjectX500Principal().getName())),
                    "dn-cn");
            if (cnMatch != null) {
                log.info("Trust anchor DN+CN match in {} — serial={}, CN={}", cnMatch.getListSource(), serialHex, targetCN);
                return cnMatch;
            }
            TrustAnchorMatch cnMatchAatl = findInAatl(
                    a -> a.getSerialNumber().toString(16).equalsIgnoreCase(serialHex)
                            && targetCN.equals(extractCN(a.getSubjectX500Principal().getName())));
            if (cnMatchAatl != null) {
                log.info("Trust anchor DN+CN match in AATL — serial={}, CN={}", serialHex, targetCN);
                return cnMatchAatl;
            }
        }

        log.info("No trust anchor found by DN: serial={}, subject={} (searched {} EUTL, {} AATL anchors)",
                serialHex, subjectDN, eutlTrustAnchors.size(), aatlTrustAnchors.size());
        return TrustAnchorMatch.builder().found(false).build();
    }

    private TrustAnchorMatch findInEutl(java.util.function.Predicate<X509Certificate> matcher, String strategy) {
        for (Map.Entry<String, X509Certificate> entry : eutlTrustAnchors.entrySet()) {
            if (matcher.test(entry.getValue())) {
                String key = entry.getKey();
                String country = key.contains(":") ? key.substring(0, key.indexOf(':')) : "EU";
                return TrustAnchorMatch.builder()
                        .found(true)
                        .listSource("EUTL:" + country)
                        .matchStrategy(strategy)
                        .serviceName(entry.getValue().getSubjectX500Principal().getName())
                        .serviceStatus("granted")
                        .build();
            }
        }
        return null;
    }

    private TrustAnchorMatch findInAatl(java.util.function.Predicate<X509Certificate> matcher) {
        for (X509Certificate anchor : aatlTrustAnchors.values()) {
            if (matcher.test(anchor)) {
                return TrustAnchorMatch.builder()
                        .found(true)
                        .listSource("AATL")
                        .matchStrategy("aatl")
                        .serviceName(anchor.getSubjectX500Principal().getName())
                        .serviceStatus("approved")
                        .build();
            }
        }
        return null;
    }

    private String extractCN(String dn) {
        if (dn == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("CN=([^,]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(dn);
        return m.find() ? m.group(1).trim() : null;
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

    /** Diagnostic: return LOTL country→URL map */
    public Map<String, String> getLotlCountries() {
        return cachedLotl != null ? new java.util.TreeMap<>(cachedLotl.getCountryTslUrls()) : Map.of();
    }

    /**
     * List loaded EUTL trust anchors matching an optional filter (substring match on key or subject DN).
     * Returns a list of maps with key, subject, serial, and country for diagnostic purposes.
     */
    public List<Map<String, String>> listEutlAnchors(String filter) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, X509Certificate> entry : eutlTrustAnchors.entrySet()) {
            String subject = entry.getValue().getSubjectX500Principal().getName();
            String key = entry.getKey();
            if (filter != null && !filter.isEmpty()
                    && !key.toLowerCase().contains(filter.toLowerCase())
                    && !subject.toLowerCase().contains(filter.toLowerCase())) {
                continue;
            }
            Map<String, String> info = new java.util.LinkedHashMap<>();
            info.put("key", key);
            info.put("subject", subject);
            info.put("serial", entry.getValue().getSerialNumber().toString(16));
            info.put("country", key.contains(":") ? key.substring(0, key.indexOf(':')) : "?");
            result.add(info);
        }
        return result;
    }

    // ── LOTL Parsing ────────────────────────────────────────────────────────────

    private void fetchLotl() {
        try {
            byte[] lotlBytes = downloadUrl(lotlUrl);
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(lotlBytes));

            Map<String, String> countryUrls = new ConcurrentHashMap<>();

            // Strategy 1: Parse OtherTSLPointer entries (standard LOTL structure)
            NodeList pointers = doc.getElementsByTagNameNS("*", "OtherTSLPointer");
            log.info("LOTL: found {} OtherTSLPointer entries", pointers.getLength());
            for (int i = 0; i < pointers.getLength(); i++) {
                try {
                    Element pointer = (Element) pointers.item(i);

                    // Extract TSLLocation — try multiple approaches
                    String tslLocation = getElementText(pointer, "TSLLocation");
                    if (tslLocation == null) {
                        tslLocation = getElementText(pointer, "tsl:TSLLocation");
                    }

                    // Extract SchemeTerritory — try multiple approaches
                    String territory = getElementText(pointer, "SchemeTerritory");
                    if (territory == null) {
                        territory = getElementText(pointer, "tsl:SchemeTerritory");
                    }
                    // Fallback: look inside AdditionalInformation/OtherInformation
                    if (territory == null) {
                        NodeList otherInfos = pointer.getElementsByTagNameNS("*", "OtherInformation");
                        for (int j = 0; j < otherInfos.getLength(); j++) {
                            Element otherInfo = (Element) otherInfos.item(j);
                            territory = getElementText(otherInfo, "SchemeTerritory");
                            if (territory != null) break;
                        }
                    }
                    // Fallback: try to extract 2-letter country code from URL path (e.g. /TSL-DE.xml, /TL_DE.xml)
                    if (territory == null && tslLocation != null) {
                        territory = inferCountryFromUrl(tslLocation);
                    }

                    log.info("LOTL pointer [{}]: territory={}, url={}", i, territory,
                            tslLocation != null ? tslLocation.substring(0, Math.min(tslLocation.length(), 120)) : "null");

                    if (tslLocation != null && territory != null) {
                        String cc = territory.toUpperCase().trim();
                        if (cc.length() == 2 && !"EU".equals(cc)) {
                            countryUrls.put(cc, tslLocation);
                        }
                    }
                } catch (Exception e) {
                    log.warn("LOTL: failed to parse pointer [{}]: {}", i, e.getMessage());
                }
            }

            // Strategy 2: If we got suspiciously few countries, also scan for all TSLLocation
            // elements paired with nearby SchemeTerritory elements
            if (countryUrls.size() < 20) {
                log.warn("LOTL: only {} countries found via OtherTSLPointer — trying broader scan", countryUrls.size());
                NodeList allLocations = doc.getElementsByTagNameNS("*", "TSLLocation");
                NodeList allTerritories = doc.getElementsByTagNameNS("*", "SchemeTerritory");
                log.info("LOTL broad scan: {} TSLLocation elements, {} SchemeTerritory elements",
                        allLocations.getLength(), allTerritories.getLength());
            }

            // Sanity check: EU LOTL should have ~30 countries. If far fewer, something went wrong.
            int expectedMin = 25;
            if (countryUrls.size() < expectedMin) {
                log.warn("LOTL sanity check FAILED: only {} countries parsed (expected >= {}). Countries: {}",
                        countryUrls.size(), expectedMin, new java.util.TreeSet<>(countryUrls.keySet()));
            }

            cachedLotl = LotlParseResult.builder()
                    .countryTslUrls(countryUrls)
                    .fetchedAt(Instant.now())
                    .lotlCountriesParsed(countryUrls.size())
                    .build();
            log.info("Parsed LOTL: {} country TSL URLs: {}", countryUrls.size(), new java.util.TreeSet<>(countryUrls.keySet()));

        } catch (Exception e) {
            log.error("Failed to fetch/parse LOTL from {}: {}", lotlUrl, e.getMessage());
        }
    }

    /**
     * Try to infer a 2-letter country code from a TSL URL.
     * Matches patterns like TSL-DE.xml, TL_DE.xml, /DE_TSL.xml, /DE-TL.xml, etc.
     */
    private String inferCountryFromUrl(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:[-_/])([A-Z]{2})[-_.]|([A-Z]{2})[-_](?:TSL|TL)",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(url);
        while (m.find()) {
            String cc = (m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase();
            // Validate it looks like a real country code (not "WW", "TL", etc.)
            if (cc.matches("[A-Z]{2}") && !"TL".equals(cc) && !"WW".equals(cc) && !"EU".equals(cc)) {
                return cc;
            }
        }
        return null;
    }

    // ── Country TSL Parsing ─────────────────────────────────────────────────────

    private boolean fetchCountryTsl(String country, String tslUrl) {
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
                        log.debug("Stored EUTL anchor: key={}, subject={}", key, cert.getSubjectX500Principal().getName());

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

            long certCount = services.stream().filter(s -> s.getCertificate() != null).count();
            log.info("Parsed TSL for {}: {} services, {} certificates (total EUTL anchors now: {})",
                    country, services.size(), certCount, eutlTrustAnchors.size());
            return true;

        } catch (Exception e) {
            log.error("Failed to fetch/parse TSL for {}: {}", country, e.getMessage());
            return false;
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
        private int lotlCountriesParsed;
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
        private String matchStrategy;    // exact, pubkey, dn-exact, dn-cn, aatl
        private String serviceName;
        private String serviceStatus;
    }
}
