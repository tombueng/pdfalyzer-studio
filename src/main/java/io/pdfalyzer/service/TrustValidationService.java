package io.pdfalyzer.service;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.util.Store;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.CertificateChainEntry;
import io.pdfalyzer.model.RevocationStatus;
import io.pdfalyzer.model.SignatureAnalysisResult;
import io.pdfalyzer.model.SignatureInfo;
import io.pdfalyzer.model.TrustListStatus;
import io.pdfalyzer.model.TrustValidationCheck;
import io.pdfalyzer.model.TrustValidationReport;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates full signature validation: trust list resolution, chain validation,
 * and revocation checking. Runs asynchronously with progress tracking per session.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrustValidationService {

    private final TrustListService trustListService;
    private final CertificateChainBuilder chainBuilder;
    private final RevocationCheckService revocationCheckService;

    // Per-session validation state
    private final ConcurrentHashMap<String, ValidationProgress> validationProgress = new ConcurrentHashMap<>();

    /**
     * Start asynchronous full validation for a session's signatures.
     * Returns immediately; caller should poll getProgress() for status updates.
     */
    public void startValidation(String sessionId, byte[] pdfBytes, SignatureAnalysisResult basicResult) {
        ValidationProgress progress = ValidationProgress.builder()
                .sessionId(sessionId)
                .status("STARTED")
                .message("Starting trust validation...")
                .build();
        validationProgress.put(sessionId, progress);

        // Run validation in background
        Thread.startVirtualThread(() -> {
            try {
                performFullValidation(sessionId, pdfBytes, basicResult);
            } catch (Exception e) {
                log.error("Trust validation failed for session {}", sessionId, e);
                progress.setStatus("ERROR");
                progress.setMessage("Validation failed: " + e.getMessage());
            }
        });
    }

    /**
     * Get current validation progress for a session.
     */
    public ValidationProgress getProgress(String sessionId) {
        return validationProgress.get(sessionId);
    }

    /**
     * Get the enhanced result after validation completes.
     */
    public SignatureAnalysisResult getValidatedResult(String sessionId) {
        ValidationProgress progress = validationProgress.get(sessionId);
        return progress != null ? progress.getEnhancedResult() : null;
    }

    @SuppressWarnings("unchecked")
    private void performFullValidation(String sessionId, byte[] pdfBytes, SignatureAnalysisResult basicResult) {
        ValidationProgress progress = validationProgress.get(sessionId);

        // Step 1: Collect raw certificates from CMS data for country resolution
        progress.setStatus("COLLECTING_CERTS");
        progress.setMessage("Extracting certificates from signatures...");

        // Collect raw certificates from CMS data for country resolution
        List<X509Certificate> rawCerts = collectRawCertificates(pdfBytes, basicResult);
        log.info("Collected {} raw certificates from CMS stores", rawCerts.size());

        // Step 2: Resolve countries and trigger EUTL fetch
        progress.setStatus("FETCHING_TRUST_LISTS");
        progress.setMessage("Resolving certificate issuers...");

        // Primary: resolve from raw X509Certificate objects
        List<String> countries = new ArrayList<>(trustListService.resolveCountriesForCertChain(rawCerts));
        // Fallback / supplement: resolve from already-parsed chain entry DNs (covers certs not embedded in CMS)
        List<String> allDns = new ArrayList<>();
        for (SignatureInfo sig : basicResult.getSignatures()) {
            if (sig.getCertificateChain() == null) continue;
            for (CertificateChainEntry entry : sig.getCertificateChain()) {
                if (entry.getSubjectDN() != null) allDns.add(entry.getSubjectDN());
                if (entry.getIssuerDN() != null) allDns.add(entry.getIssuerDN());
            }
        }
        for (String c : trustListService.resolveCountriesFromChainDns(allDns)) {
            if (!countries.contains(c)) countries.add(c);
        }
        log.info("Resolved countries for trust validation: {}", countries);

        // Fetch EUTL and AATL in parallel
        // TrustListService will auto-expand to all LOTL countries if any resolved country
        // is not in the EUTL (e.g. BM for QuoVadis — trust anchor could be in any EU TSL)
        CountDownLatch trustListLatch = new CountDownLatch(2);

        trustListService.fetchCountryTslsAsync(countries, status -> {
            progress.setEutlStatus(status);
            progress.setMessage("EUTL: " + status.getStatusMessage());
            if ("LOADED".equals(status.getStatus()) || "ERROR".equals(status.getStatus())) {
                trustListLatch.countDown();
            }
        });

        trustListService.fetchAatlAsync(status -> {
            progress.setAatlStatus(status);
            if ("LOADED".equals(status.getStatus()) || "ERROR".equals(status.getStatus())) {
                trustListLatch.countDown();
            }
        });

        // Wait for trust lists (with timeout)
        try {
            boolean completed = trustListLatch.await(300, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("Trust list fetch timed out after 300s for session {}", sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // Step 3: Extract DSS from PDF
        progress.setStatus("CHECKING_DSS");
        progress.setMessage("Checking Document Security Store...");
        RevocationCheckService.DssContents dss = revocationCheckService.extractDssFromPdf(pdfBytes);

        // Step 4: Validate each signature
        progress.setStatus("VALIDATING");
        progress.setMessage("Validating certificate chains...");

        SignatureAnalysisResult enhanced = cloneResult(basicResult);

        for (int sigIdx = 0; sigIdx < enhanced.getSignatures().size(); sigIdx++) {
            SignatureInfo sig = enhanced.getSignatures().get(sigIdx);
            if (!sig.isSigned() || sig.getCertificateChain() == null || sig.getCertificateChain().isEmpty()) {
                continue;
            }

            progress.setMessage("Validating signature " + (sigIdx + 1) + " of " + enhanced.getSignedCount() + "...");
            TrustValidationReport report = validateSignature(sig, sigIdx, rawCerts, dss);
            sig.setTrustValidation(report);

            // Update overall validation status based on trust validation
            if ("VALID".equals(report.getOverallStatus())) {
                sig.setValidationStatus("VALID");
                sig.setValidationMessage("Signature validated against trusted certificate chain");
            }
        }

        // Update counts
        int validCount = (int) enhanced.getSignatures().stream()
                .filter(s -> "VALID".equals(s.getValidationStatus())).count();
        int invalidCount = (int) enhanced.getSignatures().stream()
                .filter(s -> "INVALID".equals(s.getValidationStatus())).count();
        int indeterminateCount = (int) enhanced.getSignatures().stream()
                .filter(s -> "INDETERMINATE".equals(s.getValidationStatus())).count();
        enhanced.setValidCount(validCount);
        enhanced.setInvalidCount(invalidCount);
        enhanced.setIndeterminateCount(indeterminateCount);
        enhanced.setEutlStatus(trustListService.getEutlStatus());
        enhanced.setAatlStatus(trustListService.getAatlStatus());

        // Done
        progress.setStatus("COMPLETED");
        progress.setMessage("Validation complete");
        progress.setEnhancedResult(enhanced);
    }

    private TrustValidationReport validateSignature(SignatureInfo sig, int sigIdx,
                                                     List<X509Certificate> rawCerts,
                                                     RevocationCheckService.DssContents dss) {
        List<TrustValidationCheck> checks = new ArrayList<>();

        // Check 1: Certificate validity
        for (CertificateChainEntry entry : sig.getCertificateChain()) {
            if (entry.getNotBefore() != null && entry.getNotAfter() != null) {
                try {
                    Instant now = Instant.now();
                    Instant notBefore = Instant.parse(entry.getNotBefore());
                    Instant notAfter = Instant.parse(entry.getNotAfter());
                    if (now.isBefore(notBefore)) {
                        checks.add(TrustValidationCheck.builder()
                                .checkName("CERT_VALIDITY")
                                .status("FAIL")
                                .message("Certificate not yet valid: " + entry.getSubjectDN())
                                .signatureIndex(sigIdx)
                                .build());
                    } else if (now.isAfter(notAfter)) {
                        checks.add(TrustValidationCheck.builder()
                                .checkName("CERT_VALIDITY")
                                .status("WARNING")
                                .message("Certificate expired: " + entry.getSubjectDN())
                                .signatureIndex(sigIdx)
                                .build());
                    } else {
                        checks.add(TrustValidationCheck.builder()
                                .checkName("CERT_VALIDITY")
                                .status("PASS")
                                .message("Certificate valid: " + entry.getSubjectDN())
                                .signatureIndex(sigIdx)
                                .build());
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
        }

        // Check 2: Chain completeness
        CertificateChainEntry lastEntry = sig.getCertificateChain().get(sig.getCertificateChain().size() - 1);
        boolean chainComplete = "ROOT".equals(lastEntry.getRole()) &&
                lastEntry.getSubjectDN() != null &&
                lastEntry.getSubjectDN().equals(lastEntry.getIssuerDN());
        checks.add(TrustValidationCheck.builder()
                .checkName("CHAIN_COMPLETE")
                .status(chainComplete ? "PASS" : "WARNING")
                .message(chainComplete ? "Certificate chain is complete (root is self-signed)"
                        : "Certificate chain may be incomplete — root CA not included")
                .signatureIndex(sigIdx)
                .build());

        // Check 3: Trust anchor resolution
        boolean foundTrustAnchor = false;
        if (trustListService.hasLoadedTrustAnchors()) {
            for (int i = sig.getCertificateChain().size() - 1; i >= 0; i--) {
                CertificateChainEntry entry = sig.getCertificateChain().get(i);
                // Find matching raw certificate; fall back to DN+serial string match if not embedded in CMS
                X509Certificate rawCert = findMatchingRawCert(entry, rawCerts);
                log.info("Trust anchor check [{}]: role={}, serial={}, rawCertFound={}, subject={}",
                        i, entry.getRole(), entry.getSerialNumber(), rawCert != null, entry.getSubjectDN());
                TrustListService.TrustAnchorMatch match = rawCert != null
                        ? trustListService.findTrustAnchor(rawCert)
                        : trustListService.findTrustAnchorByDn(entry.getSubjectDN(), entry.getSerialNumber());
                log.info("Trust anchor result [{}]: found={}, strategy={}, source={}",
                        i, match.isFound(), match.getMatchStrategy(), match.getListSource());
                if (match.isFound()) {
                    entry.setTrustAnchor(true);
                    entry.setTrustListSource(match.getListSource());
                    entry.setTrustServiceName(match.getServiceName());
                    entry.setTrustServiceStatus(match.getServiceStatus());
                    foundTrustAnchor = true;
                    String matchDetail = match.getMatchStrategy() != null
                            ? " (via " + match.getMatchStrategy() + ")" : "";
                    checks.add(TrustValidationCheck.builder()
                            .checkName("TRUST_ANCHOR")
                            .status("PASS")
                            .message("Trust anchor found in " + match.getListSource() + matchDetail + ": " + extractCN(entry.getSubjectDN()))
                            .signatureIndex(sigIdx)
                            .build());
                    break;
                }
            }
            if (!foundTrustAnchor) {
                checks.add(TrustValidationCheck.builder()
                        .checkName("TRUST_ANCHOR")
                        .status("WARNING")
                        .message("No trust anchor found in EUTL or AATL")
                        .signatureIndex(sigIdx)
                        .build());
            }
        } else {
            checks.add(TrustValidationCheck.builder()
                    .checkName("TRUST_ANCHOR")
                    .status("SKIP")
                    .message("Trust lists not loaded — cannot verify trust anchor")
                    .signatureIndex(sigIdx)
                    .build());
        }

        // Check 4: Revocation status
        for (CertificateChainEntry entry : sig.getCertificateChain()) {
            if ("ROOT".equals(entry.getRole())) continue; // Root CAs don't need revocation check

            X509Certificate rawCert = findMatchingRawCert(entry, rawCerts);
            X509Certificate rawIssuer = findIssuerCert(entry, sig.getCertificateChain(), rawCerts);

            if (rawCert != null && rawIssuer != null) {
                RevocationStatus revStatus = revocationCheckService.checkWithDssFallback(
                        rawCert, rawIssuer,
                        entry.getOcspResponderUrls(), entry.getCrlDistributionPoints(),
                        dss);
                entry.setRevocationStatus(revStatus);

                String checkStatus = switch (revStatus.getStatus()) {
                    case "GOOD" -> "PASS";
                    case "REVOKED" -> "FAIL";
                    case "ERROR", "NOT_CHECKED" -> "SKIP";
                    default -> "WARNING";
                };
                checks.add(TrustValidationCheck.builder()
                        .checkName("REVOCATION")
                        .status(checkStatus)
                        .message(entry.getRole() + " cert revocation: " + revStatus.getStatus()
                                + (revStatus.getCheckedVia() != null ? " (via " + revStatus.getCheckedVia() + ")" : ""))
                        .signatureIndex(sigIdx)
                        .build());
            }
        }

        // Check 5: TSA chain validation (if timestamp present)
        if (sig.isHasTsa() && sig.getTsaCertificateChain() != null && !sig.getTsaCertificateChain().isEmpty()) {
            // TSA certificate validity
            for (CertificateChainEntry tsaEntry : sig.getTsaCertificateChain()) {
                if (tsaEntry.getNotBefore() != null && tsaEntry.getNotAfter() != null) {
                    try {
                        Instant now = Instant.now();
                        Instant notBefore = Instant.parse(tsaEntry.getNotBefore());
                        Instant notAfter = Instant.parse(tsaEntry.getNotAfter());
                        String status;
                        String message;
                        if (now.isBefore(notBefore)) {
                            status = "FAIL";
                            message = "TSA certificate not yet valid: " + tsaEntry.getSubjectDN();
                        } else if (now.isAfter(notAfter)) {
                            status = "WARNING";
                            message = "TSA certificate expired: " + tsaEntry.getSubjectDN();
                        } else {
                            status = "PASS";
                            message = "TSA certificate valid: " + tsaEntry.getSubjectDN();
                        }
                        checks.add(TrustValidationCheck.builder()
                                .checkName("TSA_CERT_VALIDITY")
                                .status(status)
                                .message(message)
                                .signatureIndex(sigIdx)
                                .build());
                    } catch (Exception e) {
                        // Skip parse errors
                    }
                }
            }

            // TSA trust anchor resolution
            if (trustListService.hasLoadedTrustAnchors()) {
                boolean foundTsaTrustAnchor = false;
                for (int i = sig.getTsaCertificateChain().size() - 1; i >= 0; i--) {
                    CertificateChainEntry tsaEntry = sig.getTsaCertificateChain().get(i);
                    X509Certificate rawCert = findMatchingRawCert(tsaEntry, rawCerts);
                    TrustListService.TrustAnchorMatch match = rawCert != null
                            ? trustListService.findTrustAnchor(rawCert)
                            : trustListService.findTrustAnchorByDn(tsaEntry.getSubjectDN(), tsaEntry.getSerialNumber());
                    if (match.isFound()) {
                        tsaEntry.setTrustAnchor(true);
                        tsaEntry.setTrustListSource(match.getListSource());
                        tsaEntry.setTrustServiceName(match.getServiceName());
                        tsaEntry.setTrustServiceStatus(match.getServiceStatus());
                        foundTsaTrustAnchor = true;
                        String tsaMatchDetail = match.getMatchStrategy() != null
                                ? " (via " + match.getMatchStrategy() + ")" : "";
                        checks.add(TrustValidationCheck.builder()
                                .checkName("TSA_TRUST_ANCHOR")
                                .status("PASS")
                                .message("TSA trust anchor found in " + match.getListSource() + tsaMatchDetail + ": " + extractCN(tsaEntry.getSubjectDN()))
                                .signatureIndex(sigIdx)
                                .build());
                        break;
                    }
                }
                if (!foundTsaTrustAnchor) {
                    checks.add(TrustValidationCheck.builder()
                            .checkName("TSA_TRUST_ANCHOR")
                            .status("WARNING")
                            .message("No TSA trust anchor found in EUTL or AATL")
                            .signatureIndex(sigIdx)
                            .build());
                }
            }

            // TSA revocation checks
            for (CertificateChainEntry tsaEntry : sig.getTsaCertificateChain()) {
                if ("ROOT".equals(tsaEntry.getRole())) continue;
                X509Certificate rawCert = findMatchingRawCert(tsaEntry, rawCerts);
                X509Certificate rawIssuer = findIssuerCert(tsaEntry, sig.getTsaCertificateChain(), rawCerts);
                if (rawCert != null && rawIssuer != null) {
                    RevocationStatus revStatus = revocationCheckService.checkWithDssFallback(
                            rawCert, rawIssuer,
                            tsaEntry.getOcspResponderUrls(), tsaEntry.getCrlDistributionPoints(),
                            dss);
                    tsaEntry.setRevocationStatus(revStatus);
                    String checkStatus = switch (revStatus.getStatus()) {
                        case "GOOD" -> "PASS";
                        case "REVOKED" -> "FAIL";
                        case "ERROR", "NOT_CHECKED" -> "SKIP";
                        default -> "WARNING";
                    };
                    checks.add(TrustValidationCheck.builder()
                            .checkName("TSA_REVOCATION")
                            .status(checkStatus)
                            .message("TSA " + tsaEntry.getRole() + " cert revocation: " + revStatus.getStatus()
                                    + (revStatus.getCheckedVia() != null ? " (via " + revStatus.getCheckedVia() + ")" : ""))
                            .signatureIndex(sigIdx)
                            .build());
                }
            }
        }

        // Check 6: DSS certificate coverage
        if (dss != null && dss.isHasDss() && dss.getDssCerts() != null && !dss.getDssCerts().isEmpty()) {
            List<String> inDss = new ArrayList<>();
            List<String> missingFromDss = new ArrayList<>();

            // Check signer chain certificates
            for (CertificateChainEntry entry : sig.getCertificateChain()) {
                boolean found = isCertInDss(entry, rawCerts, dss.getDssCerts());
                entry.setPresentInDss(found);
                String label = extractCN(entry.getSubjectDN());
                if (found) {
                    inDss.add(label + " [" + entry.getRole() + "]");
                } else {
                    missingFromDss.add(label + " [" + entry.getRole() + "]");
                }
            }

            // Check TSA chain certificates
            if (sig.isHasTsa() && sig.getTsaCertificateChain() != null) {
                for (CertificateChainEntry tsaEntry : sig.getTsaCertificateChain()) {
                    boolean found = isCertInDss(tsaEntry, rawCerts, dss.getDssCerts());
                    tsaEntry.setPresentInDss(found);
                    String label = extractCN(tsaEntry.getSubjectDN()) + " [TSA/" + tsaEntry.getRole() + "]";
                    if (found) {
                        inDss.add(label);
                    } else {
                        missingFromDss.add(label);
                    }
                }
            }

            String statusStr;
            String messageStr;
            if (missingFromDss.isEmpty()) {
                statusStr = "PASS";
                messageStr = "All " + inDss.size() + " chain certificates are present in DSS";
            } else {
                statusStr = "WARNING";
                messageStr = inDss.size() + " cert(s) in DSS, " + missingFromDss.size()
                        + " missing: " + String.join(", ", missingFromDss);
            }
            checks.add(TrustValidationCheck.builder()
                    .checkName("DSS_CERT_COVERAGE")
                    .status(statusStr)
                    .message(messageStr)
                    .signatureIndex(sigIdx)
                    .build());
        }

        // Check 7: Byte range coverage
        checks.add(TrustValidationCheck.builder()
                .checkName("BYTE_RANGE")
                .status(sig.isCoversEntireFile() ? "PASS" : "WARNING")
                .message(sig.isCoversEntireFile() ? "Signature covers entire file"
                        : "Signature does not cover entire file — modifications may exist")
                .signatureIndex(sigIdx)
                .build());

        // Check 8: DSS present
        checks.add(TrustValidationCheck.builder()
                .checkName("DSS_PRESENT")
                .status(dss != null && dss.isHasDss() ? "PASS" : "SKIP")
                .message(dss != null && dss.isHasDss()
                        ? "Document Security Store found (" + dss.getEmbeddedOcspResponses().size() + " OCSP, "
                                + dss.getEmbeddedCrls().size() + " CRL)"
                        : "No Document Security Store in PDF")
                .signatureIndex(sigIdx)
                .build());

        // Determine overall status
        boolean hasFail = checks.stream().anyMatch(c -> "FAIL".equals(c.getStatus()));
        boolean hasWarning = checks.stream().anyMatch(c -> "WARNING".equals(c.getStatus()));
        boolean hasSkip = checks.stream().anyMatch(c -> "SKIP".equals(c.getStatus()));

        String overall;
        String overallMessage;
        if (hasFail) {
            overall = "INVALID";
            overallMessage = "Validation failed — see individual checks for details";
        } else if (foundTrustAnchor && !hasWarning) {
            overall = "VALID";
            overallMessage = "Signature validated against trusted certificate chain";
        } else if (hasWarning || hasSkip) {
            overall = "INDETERMINATE";
            overallMessage = "Some checks could not be completed — see details";
        } else {
            overall = "INDETERMINATE";
            overallMessage = "Validation incomplete";
        }

        return TrustValidationReport.builder()
                .checks(checks)
                .overallStatus(overall)
                .overallMessage(overallMessage)
                .eutlStatus(trustListService.getEutlStatus())
                .aatlStatus(trustListService.getAatlStatus())
                .build();
    }

    private X509Certificate findMatchingRawCert(CertificateChainEntry entry, List<X509Certificate> rawCerts) {
        for (X509Certificate cert : rawCerts) {
            if (cert.getSerialNumber().toString(16).equals(entry.getSerialNumber())
                    && cert.getSubjectX500Principal().getName().equals(entry.getSubjectDN())) {
                return cert;
            }
        }
        log.debug("No raw cert match for chain entry: serial={}, subject={} (searched {} raw certs)",
                entry.getSerialNumber(), entry.getSubjectDN(), rawCerts.size());
        return null;
    }

    private X509Certificate findIssuerCert(CertificateChainEntry entry,
                                            List<CertificateChainEntry> chain,
                                            List<X509Certificate> rawCerts) {
        // Find the next cert in the chain (issuer of this one)
        for (CertificateChainEntry other : chain) {
            if (other.getSubjectDN() != null && other.getSubjectDN().equals(entry.getIssuerDN())
                    && other.getChainIndex() > entry.getChainIndex()) {
                return findMatchingRawCert(other, rawCerts);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<X509Certificate> collectRawCertificates(byte[] pdfBytes, SignatureAnalysisResult result) {
        List<X509Certificate> certs = new ArrayList<>();
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            var acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) return certs;

            for (var field : acroForm.getFieldTree()) {
                if (field instanceof org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField sf) {
                    var sig = sf.getSignature();
                    if (sig == null) continue;
                    try {
                        byte[] contents = sig.getContents(pdfBytes);
                        if (contents == null || contents.length == 0) continue;
                        CMSSignedData cmsData = new CMSSignedData(contents);
                        Store<X509CertificateHolder> certStore = cmsData.getCertificates();
                        JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
                        for (X509CertificateHolder holder : (java.util.Collection<X509CertificateHolder>) certStore.getMatches(null)) {
                            certs.add(converter.getCertificate(holder));
                        }

                        // Also extract TSA certificates from timestamp token
                        for (SignerInformation signer : cmsData.getSignerInfos().getSigners()) {
                            CertificateChainBuilder.TsaChainResult tsaResult = chainBuilder.extractTsaChain(cmsData, signer);
                            if (tsaResult.hasTsa() && tsaResult.rawCerts() != null) {
                                certs.addAll(tsaResult.rawCerts());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to extract certs from signature {}: {}", sf.getPartialName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to collect raw certificates", e);
        }
        return certs;
    }

    private boolean isCertInDss(CertificateChainEntry entry, List<X509Certificate> rawCerts,
                                List<X509Certificate> dssCerts) {
        X509Certificate rawCert = findMatchingRawCert(entry, rawCerts);
        if (rawCert == null) return false;

        for (X509Certificate dssCert : dssCerts) {
            if (rawCert.getSerialNumber().equals(dssCert.getSerialNumber())
                    && rawCert.getSubjectX500Principal().equals(dssCert.getSubjectX500Principal())) {
                return true;
            }
        }
        return false;
    }

    private String extractCN(String dn) {
        if (dn == null) return "unknown";
        var match = java.util.regex.Pattern.compile("CN=([^,]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(dn);
        return match.find() ? match.group(1).trim() : dn;
    }

    private SignatureAnalysisResult cloneResult(SignatureAnalysisResult original) {
        // Create a mutable copy
        return SignatureAnalysisResult.builder()
                .totalSignatureFields(original.getTotalSignatureFields())
                .signedCount(original.getSignedCount())
                .unsignedCount(original.getUnsignedCount())
                .validCount(original.getValidCount())
                .invalidCount(original.getInvalidCount())
                .indeterminateCount(original.getIndeterminateCount())
                .hasCertificationSignature(original.isHasCertificationSignature())
                .signatures(new ArrayList<>(original.getSignatures()))
                .revisions(original.getRevisions())
                .build();
    }

    @Data
    @Builder
    public static class ValidationProgress {
        private String sessionId;
        private String status;   // STARTED, COLLECTING_CERTS, FETCHING_TRUST_LISTS, CHECKING_DSS, VALIDATING, COMPLETED, ERROR
        private String message;
        private TrustListStatus eutlStatus;
        private TrustListStatus aatlStatus;
        private SignatureAnalysisResult enhancedResult;
    }
}
