package io.pdfalyzer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests public methods in {@link CertificateChainBuilder}.
 */
class CertificateChainBuilderTest {

    private static KeyPair rootKp;
    private static KeyPair intermediateKp;
    private static KeyPair leafKp;
    private static X509Certificate rootCert;
    private static X509Certificate intermediateCert;
    private static X509Certificate leafCert;

    @BeforeAll
    static void setup() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        rootKp = kpg.generateKeyPair();
        intermediateKp = kpg.generateKeyPair();
        leafKp = kpg.generateKeyPair();

        Date notBefore = new Date(System.currentTimeMillis() - 86400_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 365 * 86400_000L);

        X500Principal rootDn = new X500Principal("CN=Test Root CA, O=Test, C=DE");
        X500Principal intDn = new X500Principal("CN=Test Intermediate CA, O=Test, C=DE");
        X500Principal leafDn = new X500Principal("CN=Test Leaf, O=Test, C=DE");

        rootCert = buildCert(rootDn, rootDn, BigInteger.valueOf(1), rootKp, rootKp, notBefore, notAfter);
        intermediateCert = buildCert(intDn, rootDn, BigInteger.valueOf(2), intermediateKp, rootKp, notBefore, notAfter);
        leafCert = buildCert(leafDn, intDn, BigInteger.valueOf(3), leafKp, intermediateKp, notBefore, notAfter);
    }

    private static X509Certificate buildCert(X500Principal subject, X500Principal issuer,
            BigInteger serial, KeyPair subjectKp, KeyPair issuerKp,
            Date notBefore, Date notAfter) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(issuerKp.getPrivate());
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subject, subjectKp.getPublic());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    @Test
    void formatChainAsPem_containsPemMarkers() {
        CertificateChainBuilder cb = new CertificateChainBuilder();
        String pem = cb.formatChainAsPem(List.of(leafCert, intermediateCert, rootCert));
        assertThat(pem).contains("-----BEGIN CERTIFICATE-----");
        assertThat(pem).contains("-----END CERTIFICATE-----");
    }

    @Test
    void formatChainAsPem_containsThreeCerts() {
        CertificateChainBuilder cb = new CertificateChainBuilder();
        String pem = cb.formatChainAsPem(List.of(leafCert, intermediateCert, rootCert));
        // Should contain 3 BEGIN markers
        long count = pem.lines().filter(l -> l.contains("-----BEGIN CERTIFICATE-----")).count();
        assertThat(count).isEqualTo(3);
    }

    @Test
    void formatChainAsPem_containsRoleComments() {
        CertificateChainBuilder cb = new CertificateChainBuilder();
        String pem = cb.formatChainAsPem(List.of(leafCert, intermediateCert, rootCert));
        assertThat(pem).contains("Subject:");
        assertThat(pem).contains("Issuer:");
        assertThat(pem).contains("Serial:");
    }

    @Test
    void formatChainAsPem_emptyChainReturnsEmpty() {
        CertificateChainBuilder cb = new CertificateChainBuilder();
        String pem = cb.formatChainAsPem(List.of());
        assertThat(pem).isEmpty();
    }

    @Test
    void tsaChainResult_emptyHasNoTsa() {
        CertificateChainBuilder.TsaChainResult empty = CertificateChainBuilder.TsaChainResult.empty();
        assertThat(empty.hasTsa()).isFalse();
        assertThat(empty.chain()).isEmpty();
        assertThat(empty.rawCerts()).isEmpty();
    }
}
