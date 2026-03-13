package io.pdfalyzer.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import eu.europa.esig.dss.enumerations.CertificationPermission;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.EncryptionAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.enumerations.SignerTextPosition;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.pades.DSSFileFont;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.SignatureFieldParameters;
import eu.europa.esig.dss.pades.SignatureImageParameters;
import eu.europa.esig.dss.pades.SignatureImageTextParameters;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.model.x509.CertificateToken;
import io.pdfalyzer.model.PendingSignatureData;
import io.pdfalyzer.model.SigningKeyMaterial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PdfSigningService {

    /**
     * Apply a digital signature to the given PDF bytes using the EU DSS library.
     * Supports PAdES B-B, B-T (timestamp), B-LT (long-term with DSS dict), and B-LTA.
     * For B-LT/B-LTA, configures online OCSP/CRL sources so the DSS dictionary is populated.
     * Returns the new (incrementally saved) PDF bytes.
     */
    public byte[] signDocument(byte[] pdfBytes, PendingSignatureData pending, SigningKeyMaterial keyMaterial) {
        try {
            // 1. Pre-process: embed biometric data into the PDF catalog before signing
            //    so that it is covered by the signature's byte range
            byte[] preparedPdf = embedBiometricIfNeeded(pdfBytes, pending);

            // 2. Configure PAdES signature parameters
            PAdESSignatureParameters parameters = buildParameters(pending, keyMaterial);

            // 3. Create the PAdES service with PDFBox backend
            CommonCertificateVerifier verifier = buildCertificateVerifier(pending, keyMaterial);
            PAdESService service = new PAdESService(verifier);
            service.setPdfObjFactory(new PdfBoxNativeObjectFactory());

            // 4. Configure TSP source for B-T profile
            boolean useTsa = isTimestampProfile(pending);
            if (useTsa) {
                OnlineTSPSource tspSource = new OnlineTSPSource(pending.getTsaUrl());
                service.setTspSource(tspSource);
                log.info("TSA configured for PAdES B-T: {}", pending.getTsaUrl());
            }

            // 5. Get data to sign
            DSSDocument toSign = new InMemoryDocument(preparedPdf, "document.pdf");
            ToBeSigned dataToSign = service.getDataToSign(toSign, parameters);

            // 6. Sign the data using our in-memory private key
            SignatureAlgorithm sigAlgo = resolveSignatureAlgorithm(keyMaterial.getPrivateKey());
            byte[] signedBytes = computeSignature(dataToSign.getBytes(), keyMaterial.getPrivateKey(), sigAlgo);
            SignatureValue signatureValue = new SignatureValue(sigAlgo, signedBytes);

            // 7. Finalize the signed document (DSS handles CMS, timestamp, DSS dict)
            DSSDocument signedDoc = service.signDocument(toSign, parameters, signatureValue);

            byte[] result = toByteArray(signedDoc);
            log.info("Signed field '{}' with PAdES {} ({} bytes)",
                    pending.getFieldName(), pending.getPadesProfile(), result.length);
            return result;

        } catch (Exception e) {
            throw new IllegalStateException("Signing failed: " + e.getMessage(), e);
        }
    }

    // ── Parameter building ────────────────────────────────────────────────────

    private PAdESSignatureParameters buildParameters(PendingSignatureData pending, SigningKeyMaterial keyMaterial) {
        PAdESSignatureParameters parameters = new PAdESSignatureParameters();

        // Signature level (B-B, B-T, B-LT, B-LTA)
        parameters.setSignatureLevel(resolveSignatureLevel(pending.getPadesProfile(), pending));
        parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);

        // Signing certificate and chain
        CertificateToken sigCert = new CertificateToken(keyMaterial.getCertificate());
        parameters.setSigningCertificate(sigCert);
        List<CertificateToken> chainTokens = keyMaterial.getChain().stream()
                .map(CertificateToken::new)
                .toList();
        parameters.setCertificateChain(chainTokens);

        // Metadata
        if (pending.getReason() != null && !pending.getReason().isBlank()) {
            parameters.setReason(pending.getReason());
        }
        if (pending.getLocation() != null && !pending.getLocation().isBlank()) {
            parameters.setLocation(pending.getLocation());
        }
        if (pending.getContactInfo() != null && !pending.getContactInfo().isBlank()) {
            parameters.setContactInfo(pending.getContactInfo());
        }

        String cn = extractCN(keyMaterial.getCertificate());
        if (cn != null) {
            parameters.setSignerName(cn);
        }

        // DocMDP / certification permission
        if ("certification".equals(pending.getSignMode())) {
            parameters.setPermission(mapCertificationPermission(pending.getDocMdpLevel()));
        }

        // Visual appearance (uses existing signature field)
        configureVisualAppearance(parameters, pending);

        return parameters;
    }

    private SignatureLevel resolveSignatureLevel(String profile, PendingSignatureData pending) {
        if (profile == null) return SignatureLevel.PAdES_BASELINE_B;
        return switch (profile) {
            case "B-T" -> {
                if (pending.getTsaUrl() != null && !pending.getTsaUrl().isBlank()) {
                    yield SignatureLevel.PAdES_BASELINE_T;
                }
                log.warn("B-T profile requested but no TSA URL provided, falling back to B-B");
                yield SignatureLevel.PAdES_BASELINE_B;
            }
            case "B-LT" -> SignatureLevel.PAdES_BASELINE_LT;
            case "B-LTA" -> SignatureLevel.PAdES_BASELINE_LTA;
            default -> SignatureLevel.PAdES_BASELINE_B;
        };
    }

    private boolean isTimestampProfile(PendingSignatureData pending) {
        String profile = pending.getPadesProfile();
        return ("B-T".equals(profile) || "B-LT".equals(profile) || "B-LTA".equals(profile))
                && pending.getTsaUrl() != null && !pending.getTsaUrl().isBlank();
    }

    private CertificationPermission mapCertificationPermission(int docMdpLevel) {
        return switch (docMdpLevel) {
            case 1 -> CertificationPermission.NO_CHANGE_PERMITTED;
            case 3 -> CertificationPermission.CHANGES_PERMITTED;
            default -> CertificationPermission.MINIMAL_CHANGES_PERMITTED;
        };
    }

    // ── Visual appearance ─────────────────────────────────────────────────────

    private void configureVisualAppearance(PAdESSignatureParameters parameters, PendingSignatureData pending) {
        SignatureImageParameters imageParams = new SignatureImageParameters();

        // Always reference the existing signature field by name
        SignatureFieldParameters fieldParams = new SignatureFieldParameters();
        fieldParams.setFieldId(pending.getFieldName());
        imageParams.setFieldParameters(fieldParams);

        String mode = pending.getVisualMode();
        if (mode == null || "invisible".equals(mode)) {
            parameters.setImageParameters(imageParams);
            return;
        }

        switch (mode) {
            case "text" -> configureTextVisual(imageParams, pending);
            case "image" -> configureImageVisual(imageParams, pending.getImageDataBase64());
            case "draw" -> configureImageVisual(imageParams, pending.getDrawnImageBase64());
        }

        parameters.setImageParameters(imageParams);
    }

    private void configureTextVisual(SignatureImageParameters imageParams, PendingSignatureData pending) {
        String text = pending.getDisplayName();
        if (text == null || text.isBlank()) {
            text = "Digitally signed";
        }

        SignatureImageTextParameters textParams = new SignatureImageTextParameters();
        textParams.setText(text);
        textParams.setSignerTextPosition(SignerTextPosition.TOP);

        // Load custom font if specified
        String fontId = pending.getFontName();
        if (fontId != null && !fontId.isBlank()) {
            String resourcePath = "fonts/signing/" + fontId + ".ttf";
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is != null) {
                    textParams.setFont(new DSSFileFont(is));
                }
            } catch (IOException e) {
                log.warn("Failed to load signing font {}, using default", fontId);
            }
        }

        imageParams.setTextParameters(textParams);
    }

    private void configureImageVisual(SignatureImageParameters imageParams, String base64Data) {
        if (base64Data == null || base64Data.isBlank()) return;

        String data = base64Data;
        if (data.contains(",")) {
            data = data.substring(data.indexOf(',') + 1);
        }

        byte[] imageBytes = Base64.getDecoder().decode(data);
        imageParams.setImage(new InMemoryDocument(imageBytes, "signature-visual.png"));
    }

    // ── Biometric data embedding ──────────────────────────────────────────────

    /**
     * Embeds biometric signature data into the PDF document catalog BEFORE signing,
     * so that it is covered by the signature's byte range and is tamper-evident.
     * Stored under /PDFalyzerBiometric keyed by field name.
     */
    private byte[] embedBiometricIfNeeded(byte[] pdfBytes, PendingSignatureData pending) {
        if (pending.getBiometricData() == null || pending.getBiometricData().isBlank()) {
            return pdfBytes;
        }

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String biometricJson = pending.getBiometricData();
            String fmt = (pending.getBiometricFormat() != null) ? pending.getBiometricFormat() : "json-zip";

            byte[] payload;
            switch (fmt) {
                case "json":
                    payload = biometricJson.getBytes(StandardCharsets.UTF_8);
                    break;
                case "binary":
                    payload = encodeBiometricBinary(biometricJson);
                    fmt = "binary";
                    break;
                default: // "json-zip"
                    payload = deflateBytes(biometricJson.getBytes(StandardCharsets.UTF_8));
                    fmt = "json-zip";
                    break;
            }

            COSDictionary bioDict = new COSDictionary();
            bioDict.setName(COSName.getPDFName("Format"), fmt);
            bioDict.setInt(COSName.getPDFName("Version"), 1);
            bioDict.setItem(COSName.getPDFName("Data"), new COSString(payload));
            bioDict.setInt(COSName.getPDFName("UncompressedSize"),
                    biometricJson.getBytes(StandardCharsets.UTF_8).length);
            bioDict.setName(COSName.getPDFName("FieldName"), pending.getFieldName());
            bioDict.setString(COSName.getPDFName("Timestamp"),
                    Calendar.getInstance().toInstant().toString());

            // Store in document catalog (covered by signature byte range)
            COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();

            // Support multiple biometric entries via a parent dictionary
            COSDictionary biometricRoot;
            if (catalog.containsKey(COSName.getPDFName("PDFalyzerBiometric"))) {
                biometricRoot = catalog.getCOSDictionary(COSName.getPDFName("PDFalyzerBiometric"));
            } else {
                biometricRoot = new COSDictionary();
                catalog.setItem(COSName.getPDFName("PDFalyzerBiometric"), biometricRoot);
            }
            biometricRoot.setItem(COSName.getPDFName(pending.getFieldName()), bioDict);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            log.info("Embedded biometric data in catalog: format={}, payload={} bytes", fmt, payload.length);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to embed biometric data, signing without it: {}", e.getMessage());
            return pdfBytes;
        }
    }

    private byte[] deflateBytes(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(Deflater.BEST_COMPRESSION))) {
            dos.write(input);
        }
        return baos.toByteArray();
    }

    /**
     * Compact binary encoding: each point is stored as
     * (float32 x, float32 y, float32 pressure, int16 tiltX, int16 tiltY, float32 time).
     */
    private byte[] encodeBiometricBinary(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var strokesNode = root.get("strokes");
            if (strokesNode == null || !strokesNode.isArray()) {
                return json.getBytes(StandardCharsets.UTF_8);
            }

            int totalPoints = 0;
            for (var stroke : strokesNode) {
                totalPoints += stroke.size();
            }

            int headerSize = 8 + strokesNode.size() * 4;
            int pointSize = 20;
            ByteBuffer buf = ByteBuffer.allocate(headerSize + totalPoints * pointSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            buf.put((byte) 'B').put((byte) 'I').put((byte) 'O').put((byte) '1');
            buf.putInt(strokesNode.size());

            for (var stroke : strokesNode) {
                buf.putInt(stroke.size());
            }

            for (var stroke : strokesNode) {
                for (var pt : stroke) {
                    buf.putFloat((float) pt.get("x").asDouble());
                    buf.putFloat((float) pt.get("y").asDouble());
                    buf.putFloat((float) pt.get("p").asDouble(0.5));
                    buf.putShort((short) pt.get("tiltX").asInt(0));
                    buf.putShort((short) pt.get("tiltY").asInt(0));
                    buf.putFloat((float) pt.get("t").asDouble());
                }
            }

            byte[] result = new byte[buf.position()];
            buf.rewind();
            buf.get(result);
            return result;
        } catch (Exception e) {
            log.warn("Binary biometric encoding failed, falling back to deflated JSON: {}", e.getMessage());
            try {
                return deflateBytes(json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                return json.getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    // ── Certificate verifier (needed for DSS dictionary population) ──────────

    /**
     * Builds a CommonCertificateVerifier configured with online OCSP and CRL sources.
     * For B-LT and B-LTA profiles, DSS uses these to fetch revocation data and embed
     * it into the PDF's DSS dictionary alongside the certificate chain.
     */
    private CommonCertificateVerifier buildCertificateVerifier(PendingSignatureData pending,
                                                                SigningKeyMaterial keyMaterial) {
        CommonCertificateVerifier verifier = new CommonCertificateVerifier();

        String profile = pending.getPadesProfile();
        boolean needsRevocationData = "B-LT".equals(profile) || "B-LTA".equals(profile);

        if (needsRevocationData) {
            // Online OCSP source — DSS will query OCSP responders from AIA extensions
            verifier.setOcspSource(new OnlineOCSPSource());

            // Online CRL source — DSS will download CRLs from CDP extensions
            verifier.setCrlSource(new OnlineCRLSource());

            // Add signing chain certificates as trusted so DSS can build the chain
            // and embed all certificates + revocation data into the DSS dictionary
            CommonTrustedCertificateSource trustedSource = new CommonTrustedCertificateSource();
            for (X509Certificate chainCert : keyMaterial.getChain()) {
                trustedSource.addCertificate(new CertificateToken(chainCert));
            }
            verifier.addTrustedCertSources(trustedSource);

            log.info("Certificate verifier configured with online OCSP/CRL sources for {} profile", profile);
        }

        return verifier;
    }

    // ── Cryptographic signing ─────────────────────────────────────────────────

    private SignatureAlgorithm resolveSignatureAlgorithm(PrivateKey privateKey) {
        EncryptionAlgorithm encAlgo = EncryptionAlgorithm.forKey(privateKey);
        return SignatureAlgorithm.getAlgorithm(encAlgo, DigestAlgorithm.SHA256);
    }

    private byte[] computeSignature(byte[] dataToSign, PrivateKey privateKey,
                                     SignatureAlgorithm sigAlgo) throws Exception {
        Signature sig = Signature.getInstance(sigAlgo.getJCEId());
        sig.initSign(privateKey);
        sig.update(dataToSign);
        return sig.sign();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private byte[] toByteArray(DSSDocument document) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            document.writeTo(baos);
            return baos.toByteArray();
        }
    }

    private String extractCN(X509Certificate cert) {
        if (cert == null) return null;
        String dn = cert.getSubjectX500Principal().getName();
        var match = java.util.regex.Pattern.compile("CN=([^,]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(dn);
        return match.find() ? match.group(1).trim() : null;
    }
}
