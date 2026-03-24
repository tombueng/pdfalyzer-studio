package io.pdfalyzer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests multi-strategy trust anchor matching in {@link TrustListService}.
 *
 * Generates a synthetic 3-cert chain that mimics the D-Trust structure:
 *   - Root:         CN=D-TRUST Root CA 5 2022, O=D-Trust GmbH, C=DE  (self-signed)
 *   - Intermediate: CN=D-TRUST CA 5-22-2 2022, O=D-Trust GmbH Berlin, C=DE
 *   - End entity:   CN=Erzbistum Koeln, O=Erzbistum Koeln, C=DE
 *
 * Verifies that findTrustAnchor() and findTrustAnchorByDn() correctly match
 * certificates via exact, public-key, and CN+serial strategies.
 */
class TrustAnchorMatchingTest {

    // Shared key pairs and certs — generated once
    private static KeyPair rootKeyPair;
    private static KeyPair intermediateKeyPair;
    private static KeyPair endEntityKeyPair;
    private static X509Certificate rootCert;
    private static X509Certificate intermediateCert;
    private static X509Certificate endEntityCert;

    private TrustListService trustListService;

    @BeforeAll
    static void generateCertChain() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        rootKeyPair = kpg.generateKeyPair();
        intermediateKeyPair = kpg.generateKeyPair();
        endEntityKeyPair = kpg.generateKeyPair();

        Date notBefore = new Date(System.currentTimeMillis() - 86400_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 365 * 86400_000L);

        // Root CA (self-signed)
        X500Principal rootDN = new X500Principal("CN=D-TRUST Root CA 5 2022, O=D-Trust GmbH, C=DE");
        rootCert = buildCert(rootDN, rootDN, BigInteger.valueOf(0x71cb7a9f),
                rootKeyPair, rootKeyPair, notBefore, notAfter);

        // Intermediate CA (signed by root)
        X500Principal intDN = new X500Principal("CN=D-TRUST CA 5-22-2 2022, O=D-Trust GmbH Berlin, C=DE");
        intermediateCert = buildCert(intDN, rootDN, BigInteger.valueOf(0x6dbb52d7),
                intermediateKeyPair, rootKeyPair, notBefore, notAfter);

        // End entity (signed by intermediate)
        X500Principal eeDN = new X500Principal("CN=Erzbistum Koeln, O=Erzbistum Koeln, C=DE");
        endEntityCert = buildCert(eeDN, intDN, BigInteger.valueOf(0x751fe81d),
                endEntityKeyPair, intermediateKeyPair, notBefore, notAfter);
    }

    private static X509Certificate buildCert(X500Principal subject, X500Principal issuer,
            BigInteger serial, KeyPair subjectKP, KeyPair issuerKP,
            Date notBefore, Date notAfter) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .build(issuerKP.getPrivate());
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subject, subjectKP.getPublic());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    @BeforeEach
    void setUp() {
        trustListService = new TrustListService();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void injectEutlAnchor(String key, X509Certificate cert) throws Exception {
        Field field = TrustListService.class.getDeclaredField("eutlTrustAnchors");
        field.setAccessible(true);
        ConcurrentHashMap<String, X509Certificate> map =
                (ConcurrentHashMap<String, X509Certificate>) field.get(trustListService);
        map.put(key, cert);
    }

    // ── Tests ───────────────────────────────────────────────────────────────────

    @Test
    void certChainIsValid() throws Exception {
        // Root is self-signed
        assertThat(rootCert.getSubjectX500Principal()).isEqualTo(rootCert.getIssuerX500Principal());
        rootCert.verify(rootKeyPair.getPublic());

        // Intermediate signed by root
        assertThat(intermediateCert.getIssuerX500Principal()).isEqualTo(rootCert.getSubjectX500Principal());
        intermediateCert.verify(rootKeyPair.getPublic());

        // End entity signed by intermediate
        assertThat(endEntityCert.getIssuerX500Principal()).isEqualTo(intermediateCert.getSubjectX500Principal());
        endEntityCert.verify(intermediateKeyPair.getPublic());
    }

    @Test
    void exactMatchFindsRootCert() throws Exception {
        injectEutlAnchor("DE:d-trust-root-5-2022", rootCert);

        TrustListService.TrustAnchorMatch match = trustListService.findTrustAnchor(rootCert);

        assertThat(match.isFound()).isTrue();
        assertThat(match.getMatchStrategy()).isEqualTo("exact");
        assertThat(match.getListSource()).startsWith("EUTL:DE");
    }

    @Test
    void exactMatchFindsIntermediateCert() throws Exception {
        injectEutlAnchor("DE:d-trust-ca-5-22-2-2022", intermediateCert);

        TrustListService.TrustAnchorMatch match = trustListService.findTrustAnchor(intermediateCert);

        assertThat(match.isFound()).isTrue();
        assertThat(match.getMatchStrategy()).isEqualTo("exact");
    }

    @Test
    void pubkeyMatchWhenCertReissued() throws Exception {
        // Simulate a re-issued cert: same key pair, different serial/validity
        Date notBefore = new Date(System.currentTimeMillis() - 86400_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 730 * 86400_000L);
        X509Certificate reissuedRoot = buildCert(
                rootCert.getSubjectX500Principal(),
                rootCert.getSubjectX500Principal(),
                BigInteger.valueOf(0xDEADBEEF),  // different serial
                rootKeyPair, rootKeyPair, notBefore, notAfter);

        // Inject the original root
        injectEutlAnchor("DE:d-trust-root-5-2022", rootCert);

        // Search with the re-issued cert — exact match fails (different serial), pubkey match succeeds
        TrustListService.TrustAnchorMatch match = trustListService.findTrustAnchor(reissuedRoot);

        assertThat(match.isFound()).isTrue();
        assertThat(match.getMatchStrategy()).isEqualTo("pubkey");
    }

    @Test
    void noMatchWhenAnchorNotLoaded() throws Exception {
        // Don't inject any anchors
        TrustListService.TrustAnchorMatch match = trustListService.findTrustAnchor(endEntityCert);
        assertThat(match.isFound()).isFalse();
    }

    @Test
    void endEntityNotMatchedAsAnchor() throws Exception {
        // Only root is a trust anchor
        injectEutlAnchor("DE:d-trust-root-5-2022", rootCert);

        TrustListService.TrustAnchorMatch match = trustListService.findTrustAnchor(endEntityCert);
        assertThat(match.isFound()).isFalse();
    }

    @Test
    void findTrustAnchorByDn_exactDnMatch() throws Exception {
        injectEutlAnchor("DE:d-trust-root-5-2022", rootCert);

        String subjectDN = rootCert.getSubjectX500Principal().getName();
        String serialHex = rootCert.getSerialNumber().toString(16);

        TrustListService.TrustAnchorMatch match =
                trustListService.findTrustAnchorByDn(subjectDN, serialHex);

        assertThat(match.isFound()).isTrue();
        assertThat(match.getMatchStrategy()).isEqualTo("dn-exact");
    }

    @Test
    void findTrustAnchorByDn_cnFallbackWhenOidDiffers() throws Exception {
        injectEutlAnchor("DE:d-trust-ca-5-22-2-2022", intermediateCert);

        String realDN = intermediateCert.getSubjectX500Principal().getName();
        String serialHex = intermediateCert.getSerialNumber().toString(16);

        // Mangle the DN to simulate OID encoding difference (e.g. 2.5.4.97 organizationIdentifier)
        // This forces exact-dn match to fail, falling through to CN+serial strategy
        String mangledDN = realDN.replace("O=D-Trust GmbH Berlin",
                "2.5.4.97=#0c0e4e545244452d485242373433343630,O=D-Trust GmbH Berlin");

        TrustListService.TrustAnchorMatch match =
                trustListService.findTrustAnchorByDn(mangledDN, serialHex);

        assertThat(match.isFound()).isTrue();
        assertThat(match.getMatchStrategy()).isEqualTo("dn-cn");
    }

    @Test
    void findTrustAnchorByDn_noMatchWithWrongSerial() throws Exception {
        injectEutlAnchor("DE:d-trust-root-5-2022", rootCert);

        String subjectDN = rootCert.getSubjectX500Principal().getName();

        TrustListService.TrustAnchorMatch match =
                trustListService.findTrustAnchorByDn(subjectDN, "deadbeef");

        assertThat(match.isFound()).isFalse();
    }

    @Test
    void matchResultContainsMetadata() throws Exception {
        injectEutlAnchor("DE:d-trust-root-5-2022", rootCert);

        TrustListService.TrustAnchorMatch match = trustListService.findTrustAnchor(rootCert);

        assertThat(match.getMatchStrategy()).isNotNull();
        assertThat(match.getListSource()).contains("DE");
        assertThat(match.getServiceName()).contains("D-TRUST Root CA 5 2022");
        assertThat(match.getServiceStatus()).isEqualTo("granted");
    }

    @Test
    void notFoundResultHasFoundFalse() {
        TrustListService.TrustAnchorMatch notFound =
                TrustListService.TrustAnchorMatch.builder().found(false).build();
        assertThat(notFound.isFound()).isFalse();
        assertThat(notFound.getListSource()).isNull();
        assertThat(notFound.getMatchStrategy()).isNull();
    }

    // ── Real PEM certificate tests ───────────────────────────────────────────

    private static List<X509Certificate> loadPemCerts(String resourcePath) throws Exception {
        InputStream probe = TrustAnchorMatchingTest.class.getResourceAsStream(resourcePath);
        Assumptions.assumeTrue(probe != null,
                "PEM resource not available on classpath: " + resourcePath);
        probe.close();

        List<X509Certificate> certs = new ArrayList<>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        StringBuilder base64 = new StringBuilder();
        boolean inCert = false;

        try (InputStream is = TrustAnchorMatchingTest.class.getResourceAsStream(resourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("BEGIN CERTIFICATE")) {
                    inCert = true;
                    base64.setLength(0);
                } else if (line.contains("END CERTIFICATE")) {
                    inCert = false;
                    byte[] der = Base64.getDecoder().decode(base64.toString());
                    certs.add((X509Certificate) cf.generateCertificate(
                            new java.io.ByteArrayInputStream(der)));
                } else if (inCert && !line.startsWith("#")) {
                    base64.append(line.trim());
                }
            }
        }
        return certs;
    }

    @Test
    void realPemChain_rootIsRecognisedAsTrustAnchor() throws Exception {
        List<X509Certificate> certs = loadPemCerts("/test-eutl.pem");
        assertThat(certs).hasSize(3);

        X509Certificate endEntity = certs.get(0);
        X509Certificate intermediate = certs.get(1);
        X509Certificate root = certs.get(2);

        // Root is self-signed
        assertThat(root.getSubjectX500Principal()).isEqualTo(root.getIssuerX500Principal());

        // Inject the real root as a trust anchor
        injectEutlAnchor("DE:d-trust-root-5-2022", root);

        // Root should match exactly
        TrustListService.TrustAnchorMatch rootMatch = trustListService.findTrustAnchor(root);
        assertThat(rootMatch.isFound()).isTrue();
        assertThat(rootMatch.getMatchStrategy()).isEqualTo("exact");
    }

    @Test
    void realPemChain_intermediateMatchesWhenInjected() throws Exception {
        List<X509Certificate> certs = loadPemCerts("/test-eutl.pem");
        X509Certificate intermediate = certs.get(1);

        injectEutlAnchor("DE:d-trust-ca-5-22-2-2022", intermediate);

        TrustListService.TrustAnchorMatch match = trustListService.findTrustAnchor(intermediate);
        assertThat(match.isFound()).isTrue();
        assertThat(match.getMatchStrategy()).isEqualTo("exact");
        assertThat(match.getListSource()).startsWith("EUTL:DE");
    }

    @Test
    void realPemChain_endEntityNotRecognisedWithOnlyRootAnchor() throws Exception {
        List<X509Certificate> certs = loadPemCerts("/test-eutl.pem");
        X509Certificate endEntity = certs.get(0);
        X509Certificate root = certs.get(2);

        injectEutlAnchor("DE:d-trust-root-5-2022", root);

        TrustListService.TrustAnchorMatch match = trustListService.findTrustAnchor(endEntity);
        assertThat(match.isFound()).isFalse();
    }

    @Test
    void realPemChain_findByDnMatchesRoot() throws Exception {
        List<X509Certificate> certs = loadPemCerts("/test-eutl.pem");
        X509Certificate root = certs.get(2);

        injectEutlAnchor("DE:d-trust-root-5-2022", root);

        String subjectDN = root.getSubjectX500Principal().getName();
        String serialHex = root.getSerialNumber().toString(16);

        TrustListService.TrustAnchorMatch match = trustListService.findTrustAnchorByDn(subjectDN, serialHex);
        assertThat(match.isFound()).isTrue();
        assertThat(match.getMatchStrategy()).isEqualTo("dn-exact");
    }

    @Test
    void realPemChain_chainVerifiesCorrectly() throws Exception {
        List<X509Certificate> certs = loadPemCerts("/test-eutl.pem");
        X509Certificate endEntity = certs.get(0);
        X509Certificate intermediate = certs.get(1);
        X509Certificate root = certs.get(2);

        // Verify the chain signatures
        root.verify(root.getPublicKey());
        intermediate.verify(root.getPublicKey());
        endEntity.verify(intermediate.getPublicKey());

        // Verify issuer relationships
        assertThat(intermediate.getIssuerX500Principal()).isEqualTo(root.getSubjectX500Principal());
    }
}
