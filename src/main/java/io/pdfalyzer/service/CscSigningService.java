package io.pdfalyzer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.SignatureFieldParameters;
import eu.europa.esig.dss.pades.SignatureImageParameters;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.model.x509.CertificateToken;
import io.pdfalyzer.model.CscProvider;
import io.pdfalyzer.model.CscSignResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Integrates CSC remote signing into the PDF signing pipeline using EU DSS.
 * The flow:
 * 1. Build PAdES parameters and get data-to-be-signed from DSS
 * 2. Hash the data-to-be-signed
 * 3. Send hash to CSC provider via signHash endpoint
 * 4. Pass the raw signature value back to DSS to finalize the document
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CscSigningService {

    private final CscApiClient cscApiClient;

    /**
     * Sign a PDF field using a remote CSC provider via EU DSS PAdES service.
     */
    public byte[] signWithCsc(byte[] pdfBytes, String fieldName,
                               CscProvider provider, String accessToken,
                               String credentialId, String sad, String signAlgoOid,
                               List<String> certChainB64,
                               String reason, String location,
                               String padesProfile, String tsaUrl) {
        try {
            // Parse certificate chain
            List<X509Certificate> certChain = parseCertChain(certChainB64);
            if (certChain.isEmpty()) {
                throw new IllegalStateException("No valid certificates from CSC provider");
            }
            X509Certificate signerCert = certChain.get(0);

            // Build PAdES parameters
            PAdESSignatureParameters parameters = new PAdESSignatureParameters();
            parameters.setSignatureLevel(resolveSignatureLevel(padesProfile, tsaUrl));
            parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);

            CertificateToken sigCertToken = new CertificateToken(signerCert);
            parameters.setSigningCertificate(sigCertToken);
            List<CertificateToken> chainTokens = certChain.stream()
                    .map(CertificateToken::new)
                    .toList();
            parameters.setCertificateChain(chainTokens);

            String cn = extractCN(signerCert);
            if (cn != null) parameters.setSignerName(cn);
            if (reason != null && !reason.isBlank()) parameters.setReason(reason);
            if (location != null && !location.isBlank()) parameters.setLocation(location);

            // Reference existing signature field
            SignatureImageParameters imageParams = new SignatureImageParameters();
            SignatureFieldParameters fieldParams = new SignatureFieldParameters();
            fieldParams.setFieldId(fieldName);
            imageParams.setFieldParameters(fieldParams);
            parameters.setImageParameters(imageParams);

            // Create PAdES service
            CommonCertificateVerifier verifier = new CommonCertificateVerifier();
            PAdESService service = new PAdESService(verifier);
            service.setPdfObjFactory(new PdfBoxNativeObjectFactory());

            // Configure TSA for B-T
            boolean useTsa = tsaUrl != null && !tsaUrl.isBlank()
                    && ("B-T".equals(padesProfile) || "B-LT".equals(padesProfile) || "B-LTA".equals(padesProfile));
            if (useTsa) {
                service.setTspSource(new OnlineTSPSource(tsaUrl));
                log.info("TSA configured for CSC signing: {}", tsaUrl);
            }

            // Get data to sign from DSS
            DSSDocument toSign = new InMemoryDocument(pdfBytes, "document.pdf");
            ToBeSigned dataToSign = service.getDataToSign(toSign, parameters);

            // Hash the data-to-be-signed and send to CSC provider
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(dataToSign.getBytes());
            String hashB64 = Base64.getEncoder().encodeToString(hash);

            CscSignResult signResult = cscApiClient.signHash(
                    provider, accessToken, credentialId, sad,
                    List.of(hashB64), signAlgoOid);

            if (signResult.getSignatures() == null || signResult.getSignatures().isEmpty()) {
                throw new IllegalStateException("CSC signHash returned no signatures");
            }

            byte[] rawSignature = Base64.getDecoder().decode(signResult.getSignatures().get(0));

            // Determine signature algorithm from key type
            SignatureAlgorithm sigAlgo = resolveSignatureAlgorithmFromOid(signAlgoOid);
            SignatureValue signatureValue = new SignatureValue(sigAlgo, rawSignature);

            // Finalize the signed document
            DSSDocument signedDoc = service.signDocument(toSign, parameters, signatureValue);

            byte[] result = toByteArray(signedDoc);
            log.info("CSC-signed field '{}' via {} ({} bytes)", fieldName, provider.getName(), result.length);
            return result;

        } catch (Exception e) {
            throw new IllegalStateException("CSC signing failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private SignatureLevel resolveSignatureLevel(String profile, String tsaUrl) {
        if (profile == null) return SignatureLevel.PAdES_BASELINE_B;
        boolean hasTsa = tsaUrl != null && !tsaUrl.isBlank();
        return switch (profile) {
            case "B-T" -> hasTsa ? SignatureLevel.PAdES_BASELINE_T : SignatureLevel.PAdES_BASELINE_B;
            case "B-LT" -> SignatureLevel.PAdES_BASELINE_LT;
            case "B-LTA" -> SignatureLevel.PAdES_BASELINE_LTA;
            default -> SignatureLevel.PAdES_BASELINE_B;
        };
    }

    private SignatureAlgorithm resolveSignatureAlgorithmFromOid(String signAlgoOid) {
        if (signAlgoOid != null && signAlgoOid.contains("1.2.840.10045")) {
            return SignatureAlgorithm.ECDSA_SHA256;
        }
        return SignatureAlgorithm.RSA_SHA256;
    }

    private List<X509Certificate> parseCertChain(List<String> certsB64) {
        List<X509Certificate> chain = new ArrayList<>();
        if (certsB64 == null) return chain;
        for (String b64 : certsB64) {
            X509Certificate cert = cscApiClient.parseCertificate(b64);
            if (cert != null) chain.add(cert);
        }
        return chain;
    }

    private String extractCN(X509Certificate cert) {
        if (cert == null) return null;
        String dn = cert.getSubjectX500Principal().getName();
        var match = java.util.regex.Pattern.compile("CN=([^,]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(dn);
        return match.find() ? match.group(1).trim() : null;
    }

    private byte[] toByteArray(DSSDocument document) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            document.writeTo(baos);
            return baos.toByteArray();
        }
    }
}
