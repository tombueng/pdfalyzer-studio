package io.pdfalyzer.service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.KeyMaterialUploadResult;
import io.pdfalyzer.model.PdfSession;
import io.pdfalyzer.model.SelfSignedCertRequest;
import io.pdfalyzer.model.SigningKeyMaterial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class SelfSignedCertService {

    private final SessionService sessionService;
    private final CertificateService certificateService;

    private static final Map<Integer, String> EC_CURVE_NAMES = Map.of(
            256, "secp256r1",
            384, "secp384r1",
            521, "secp521r1"
    );

    private static final Set<Integer> VALID_RSA_SIZES = Set.of(2048, 3072, 4096, 8192);
    private static final Set<Integer> VALID_EC_SIZES = Set.of(256, 384, 521);

    private static final Set<String> VALID_RSA_SIG_ALGOS = Set.of(
            "SHA256withRSA", "SHA384withRSA", "SHA512withRSA",
            "SHA256withRSAandMGF1", "SHA384withRSAandMGF1", "SHA512withRSAandMGF1"
    );

    private static final Set<String> VALID_EC_SIG_ALGOS = Set.of(
            "SHA256withECDSA", "SHA384withECDSA", "SHA512withECDSA"
    );


    public KeyMaterialUploadResult generateAndStore(String sessionId, SelfSignedCertRequest request) {
        try {
            PdfSession session = sessionService.getSession(sessionId);
            KeyPair keyPair = generateKeyPair(request);
            String sigAlgo = resolveSignatureAlgorithm(request, keyPair);
            X500Name subject = buildSubject(request);
            X509Certificate cert = buildCertificate(keyPair, subject, request.getValidityDays(), sigAlgo);

            String keyId = java.util.UUID.randomUUID().toString();
            List<X509Certificate> chain = new ArrayList<>();
            chain.add(cert);

            SigningKeyMaterial material = SigningKeyMaterial.builder()
                    .sessionKeyId(keyId)
                    .privateKey(keyPair.getPrivate())
                    .certificate(cert)
                    .chain(chain)
                    .sourceDescription("Self-signed (" + cert.getSubjectX500Principal().getName() + ")")
                    .uploadedAt(System.currentTimeMillis())
                    .build();

            session.getSigningKeyMaterials().put(keyId, material);
            log.info("Generated self-signed cert {} for session {}: {} [{}]", keyId, sessionId, subject, sigAlgo);
            return certificateService.buildResult(material);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to generate self-signed certificate: " + e.getMessage(), e);
        }
    }

    public byte[] exportAsPkcs12(String sessionId, String sessionKeyId, String exportPassword) {
        SigningKeyMaterial material = certificateService.getKeyMaterial(sessionId, sessionKeyId);
        if (!material.isReadyToSign()) {
            throw new IllegalStateException("Key material is not complete — cannot export");
        }

        try {
            char[] pwd = exportPassword != null && !exportPassword.isEmpty() ? exportPassword.toCharArray() : "changeit".toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);

            java.security.cert.Certificate[] chainArray = material.getChain().toArray(new java.security.cert.Certificate[0]);
            ks.setKeyEntry("signing-key", material.getPrivateKey(), pwd, chainArray);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ks.store(baos, pwd);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to export as PKCS12: " + e.getMessage(), e);
        }
    }

    private KeyPair generateKeyPair(SelfSignedCertRequest request) throws Exception {
        String algo = request.getKeyAlgorithm() != null ? request.getKeyAlgorithm().toUpperCase() : "RSA";
        int size = request.getKeySize() > 0 ? request.getKeySize() : 2048;

        switch (algo) {
            case "EC" -> {
                if (!VALID_EC_SIZES.contains(size)) size = 256;
                String curveName = EC_CURVE_NAMES.get(size);
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
                kpg.initialize(new ECGenParameterSpec(curveName), new SecureRandom());
                return kpg.generateKeyPair();
            }
            case "ED25519" -> {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519", "BC");
                return kpg.generateKeyPair();
            }
            case "ED448" -> {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed448", "BC");
                return kpg.generateKeyPair();
            }
            default -> {
                if (!VALID_RSA_SIZES.contains(size)) size = 2048;
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
                kpg.initialize(size, new SecureRandom());
                return kpg.generateKeyPair();
            }
        }
    }

    private String resolveSignatureAlgorithm(SelfSignedCertRequest request, KeyPair keyPair) {
        String requested = request.getSignatureAlgorithm();
        String keyAlgo = keyPair.getPrivate().getAlgorithm();

        if ("Ed25519".equalsIgnoreCase(keyAlgo)) return "Ed25519";
        if ("Ed448".equalsIgnoreCase(keyAlgo)) return "Ed448";

        if ("EC".equalsIgnoreCase(keyAlgo) || "ECDSA".equalsIgnoreCase(keyAlgo)) {
            if (requested != null && VALID_EC_SIG_ALGOS.contains(requested)) return requested;
            int size = request.getKeySize() > 0 ? request.getKeySize() : 256;
            if (size >= 521) return "SHA512withECDSA";
            if (size >= 384) return "SHA384withECDSA";
            return "SHA256withECDSA";
        }

        // RSA
        if (requested != null && VALID_RSA_SIG_ALGOS.contains(requested)) return requested;
        int rsaSize = request.getKeySize() > 0 ? request.getKeySize() : 2048;
        if (rsaSize >= 4096) return "SHA512withRSA";
        if (rsaSize >= 3072) return "SHA384withRSA";
        return "SHA256withRSA";
    }

    private X500Name buildSubject(SelfSignedCertRequest request) {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        if (request.getCommonName() != null && !request.getCommonName().isBlank()) {
            builder.addRDN(BCStyle.CN, request.getCommonName());
        }
        if (request.getOrganization() != null && !request.getOrganization().isBlank()) {
            builder.addRDN(BCStyle.O, request.getOrganization());
        }
        if (request.getOrganizationalUnit() != null && !request.getOrganizationalUnit().isBlank()) {
            builder.addRDN(BCStyle.OU, request.getOrganizationalUnit());
        }
        if (request.getCountry() != null && !request.getCountry().isBlank()) {
            builder.addRDN(BCStyle.C, request.getCountry());
        }
        return builder.build();
    }

    private X509Certificate buildCertificate(KeyPair keyPair, X500Name subject, int validityDays, String sigAlgo) throws Exception {
        if (validityDays <= 0) validityDays = 365;
        Instant now = Instant.now();
        Date notBefore = Date.from(now);
        Date notAfter = Date.from(now.plus(validityDays, ChronoUnit.DAYS));
        BigInteger serial = new BigInteger(128, new SecureRandom());

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        certBuilder.addExtension(Extension.subjectKeyIdentifier, false,
                extUtils.createSubjectKeyIdentifier(keyPair.getPublic()));
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));

        ContentSigner signer = new JcaContentSignerBuilder(sigAlgo).setProvider("BC").build(keyPair.getPrivate());
        X509CertificateHolder holder = certBuilder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }
}
