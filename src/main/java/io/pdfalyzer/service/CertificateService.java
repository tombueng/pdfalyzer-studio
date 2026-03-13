package io.pdfalyzer.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.KeyMaterialUploadResult;
import io.pdfalyzer.model.PdfSession;
import io.pdfalyzer.model.SigningKeyMaterial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class CertificateService {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final SessionService sessionService;

    public KeyMaterialUploadResult parseAndStore(String sessionId, byte[] fileBytes, String filename, String password) {
        PdfSession session = sessionService.getSession(sessionId);
        String keyId = UUID.randomUUID().toString();

        SigningKeyMaterial material = SigningKeyMaterial.builder()
                .sessionKeyId(keyId)
                .uploadedAt(System.currentTimeMillis())
                .sourceDescription("Uploaded: " + filename)
                .build();

        parseIntoMaterial(material, fileBytes, filename, password);
        session.getSigningKeyMaterials().put(keyId, material);
        log.info("Stored key material {} for session {} from file '{}'", keyId, sessionId, filename);
        return buildResult(material);
    }

    public KeyMaterialUploadResult addToExisting(String sessionId, String sessionKeyId, byte[] fileBytes, String filename, String password) {
        PdfSession session = sessionService.getSession(sessionId);
        SigningKeyMaterial material = session.getSigningKeyMaterials().get(sessionKeyId);
        if (material == null) {
            throw new NoSuchElementException("Key material not found: " + sessionKeyId);
        }

        parseIntoMaterial(material, fileBytes, filename, password);
        material.setSourceDescription(material.getSourceDescription() + " + " + filename);
        log.info("Added to key material {} for session {} from file '{}'", sessionKeyId, sessionId, filename);
        return buildResult(material);
    }

    public List<KeyMaterialUploadResult> listKeyMaterials(String sessionId) {
        PdfSession session = sessionService.getSession(sessionId);
        List<KeyMaterialUploadResult> results = new ArrayList<>();
        for (SigningKeyMaterial mat : session.getSigningKeyMaterials().values()) {
            results.add(buildResult(mat));
        }
        return results;
    }

    public KeyMaterialUploadResult getKeyMaterialStatus(String sessionId, String sessionKeyId) {
        PdfSession session = sessionService.getSession(sessionId);
        SigningKeyMaterial material = session.getSigningKeyMaterials().get(sessionKeyId);
        if (material == null) {
            throw new NoSuchElementException("Key material not found: " + sessionKeyId);
        }
        return buildResult(material);
    }

    public SigningKeyMaterial getKeyMaterial(String sessionId, String sessionKeyId) {
        PdfSession session = sessionService.getSession(sessionId);
        SigningKeyMaterial material = session.getSigningKeyMaterials().get(sessionKeyId);
        if (material == null) {
            throw new NoSuchElementException("Key material not found: " + sessionKeyId);
        }
        return material;
    }

    public void deleteKeyMaterial(String sessionId, String sessionKeyId) {
        PdfSession session = sessionService.getSession(sessionId);
        if (session.getSigningKeyMaterials().remove(sessionKeyId) == null) {
            throw new NoSuchElementException("Key material not found: " + sessionKeyId);
        }
        log.info("Deleted key material {} from session {}", sessionKeyId, sessionId);
    }

    // ── Parsing logic ──────────────────────────────────────────────────────

    private void parseIntoMaterial(SigningKeyMaterial material, byte[] fileBytes, String filename, String password) {
        String lowerName = filename != null ? filename.toLowerCase() : "";
        char[] pwd = password != null && !password.isEmpty() ? password.toCharArray() : null;

        if (lowerName.endsWith(".p12") || lowerName.endsWith(".pfx")) {
            parsePkcs12(material, fileBytes, pwd);
        } else if (lowerName.endsWith(".jks")) {
            parseJks(material, fileBytes, pwd);
        } else if (lowerName.endsWith(".pem") || lowerName.endsWith(".key") || lowerName.endsWith(".crt") || lowerName.endsWith(".cert")) {
            parsePem(material, fileBytes, pwd);
        } else if (lowerName.endsWith(".der") || lowerName.endsWith(".cer")) {
            parseDerCertificate(material, fileBytes);
        } else {
            // Auto-detect: try PKCS12 first, then PEM, then DER cert
            if (!tryParsePkcs12(material, fileBytes, pwd)
                    && !tryParsePem(material, fileBytes, pwd)
                    && !tryParseDerCertificate(material, fileBytes)) {
                throw new IllegalArgumentException("Could not detect key material format for: " + filename);
            }
        }
    }

    private void parsePkcs12(SigningKeyMaterial material, byte[] fileBytes, char[] password) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(fileBytes), password);
            extractFromKeyStore(material, ks, password);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse PKCS12: " + e.getMessage(), e);
        }
    }

    private boolean tryParsePkcs12(SigningKeyMaterial material, byte[] fileBytes, char[] password) {
        try {
            parsePkcs12(material, fileBytes, password);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void parseJks(SigningKeyMaterial material, byte[] fileBytes, char[] password) {
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new ByteArrayInputStream(fileBytes), password);
            extractFromKeyStore(material, ks, password);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JKS: " + e.getMessage(), e);
        }
    }

    private void extractFromKeyStore(SigningKeyMaterial material, KeyStore ks, char[] password) throws Exception {
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (ks.isKeyEntry(alias)) {
                PrivateKey pk = (PrivateKey) ks.getKey(alias, password);
                if (pk != null && material.getPrivateKey() == null) {
                    material.setPrivateKey(pk);
                }
                java.security.cert.Certificate[] certChain = ks.getCertificateChain(alias);
                if (certChain != null && certChain.length > 0) {
                    List<X509Certificate> chain = new ArrayList<>();
                    for (java.security.cert.Certificate c : certChain) {
                        if (c instanceof X509Certificate x509) {
                            chain.add(x509);
                        }
                    }
                    if (!chain.isEmpty()) {
                        if (material.getCertificate() == null) {
                            material.setCertificate(chain.get(0));
                        }
                        material.setChain(chain);
                    }
                }
            } else if (ks.isCertificateEntry(alias)) {
                java.security.cert.Certificate c = ks.getCertificate(alias);
                if (c instanceof X509Certificate x509 && material.getCertificate() == null) {
                    material.setCertificate(x509);
                    if (material.getChain().isEmpty()) {
                        List<X509Certificate> chain = new ArrayList<>();
                        chain.add(x509);
                        material.setChain(chain);
                    }
                }
            }
        }
    }

    private void parsePem(SigningKeyMaterial material, byte[] fileBytes, char[] password) {
        try {
            parsePemInternal(material, fileBytes, password);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse PEM: " + e.getMessage(), e);
        }
    }

    private boolean tryParsePem(SigningKeyMaterial material, byte[] fileBytes, char[] password) {
        try {
            parsePemInternal(material, fileBytes, password);
            return material.getPrivateKey() != null || material.getCertificate() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void parsePemInternal(SigningKeyMaterial material, byte[] fileBytes, char[] password) throws IOException {
        JcaPEMKeyConverter keyConverter = new JcaPEMKeyConverter().setProvider("BC");
        JcaX509CertificateConverter certConverter = new JcaX509CertificateConverter().setProvider("BC");

        try (PEMParser parser = new PEMParser(new InputStreamReader(new ByteArrayInputStream(fileBytes), StandardCharsets.UTF_8))) {
            Object obj;
            while ((obj = parser.readObject()) != null) {
                try {
                    if (obj instanceof PEMKeyPair pemKeyPair) {
                        if (material.getPrivateKey() == null) {
                            material.setPrivateKey(keyConverter.getKeyPair(pemKeyPair).getPrivate());
                        }
                    } else if (obj instanceof PrivateKeyInfo pkInfo) {
                        if (material.getPrivateKey() == null) {
                            material.setPrivateKey(keyConverter.getPrivateKey(pkInfo));
                        }
                    } else if (obj instanceof X509CertificateHolder certHolder) {
                        X509Certificate cert = certConverter.getCertificate(certHolder);
                        if (material.getCertificate() == null) {
                            material.setCertificate(cert);
                        }
                        material.getChain().add(cert);
                    } else if (obj instanceof org.bouncycastle.openssl.PEMEncryptedKeyPair encryptedPair) {
                        if (password == null) {
                            throw new IllegalArgumentException("Encrypted PEM key requires a password");
                        }
                        var decryptor = new org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder()
                                .build(password);
                        PEMKeyPair decrypted = encryptedPair.decryptKeyPair(decryptor);
                        if (material.getPrivateKey() == null) {
                            material.setPrivateKey(keyConverter.getKeyPair(decrypted).getPrivate());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Skipping PEM object of type {}: {}", obj.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    private void parseDerCertificate(SigningKeyMaterial material, byte[] fileBytes) {
        try {
            parseDerCertificateInternal(material, fileBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse DER certificate: " + e.getMessage(), e);
        }
    }

    private boolean tryParseDerCertificate(SigningKeyMaterial material, byte[] fileBytes) {
        try {
            parseDerCertificateInternal(material, fileBytes);
            return material.getCertificate() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void parseDerCertificateInternal(SigningKeyMaterial material, byte[] fileBytes) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(fileBytes));
        if (material.getCertificate() == null) {
            material.setCertificate(cert);
        }
        material.getChain().add(cert);
    }

    // ── Result building ────────────────────────────────────────────────────

    KeyMaterialUploadResult buildResult(SigningKeyMaterial material) {
        KeyMaterialUploadResult.KeyMaterialUploadResultBuilder b = KeyMaterialUploadResult.builder()
                .sessionKeyId(material.getSessionKeyId())
                .hasPrivateKey(material.hasPrivateKey())
                .hasCertificate(material.hasCertificate())
                .hasCertChain(material.hasCertChain())
                .chainLength(material.getChain() != null ? material.getChain().size() : 0)
                .readyToSign(material.isReadyToSign());

        List<String> missing = new ArrayList<>();
        if (!material.hasPrivateKey()) missing.add("Private Key");
        if (!material.hasCertificate()) missing.add("Certificate");
        if (!material.hasCertChain()) missing.add("Certificate Chain (only end-entity cert present)");
        b.missingElements(missing);

        List<String> warnings = new ArrayList<>();
        X509Certificate cert = material.getCertificate();
        if (cert != null) {
            b.subjectDN(cert.getSubjectX500Principal().getName());
            b.issuerDN(cert.getIssuerX500Principal().getName());
            b.serialNumber(cert.getSerialNumber().toString(16));
            b.notBefore(cert.getNotBefore().toInstant().atOffset(ZoneOffset.UTC).format(ISO_FMT));
            b.notAfter(cert.getNotAfter().toInstant().atOffset(ZoneOffset.UTC).format(ISO_FMT));
            b.keyAlgorithm(cert.getPublicKey().getAlgorithm());
            b.keySize(getKeySize(cert));

            if (cert.getNotAfter().before(new java.util.Date())) {
                warnings.add("Certificate has expired (valid until " + cert.getNotAfter() + ")");
            }
        }
        b.warnings(warnings);
        return b.build();
    }

    private int getKeySize(X509Certificate cert) {
        try {
            if (cert.getPublicKey() instanceof java.security.interfaces.RSAPublicKey rsa) {
                return rsa.getModulus().bitLength();
            }
            if (cert.getPublicKey() instanceof java.security.interfaces.ECPublicKey ec) {
                return ec.getParams().getOrder().bitLength();
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }
}
