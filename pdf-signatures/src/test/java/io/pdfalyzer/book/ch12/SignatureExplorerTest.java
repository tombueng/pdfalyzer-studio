package io.pdfalyzer.book.ch12;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chapter 12 — Tests for SignatureExplorer.
 * <p>
 * Creates a test PDF with a signed signature field and verifies that
 * every aspect of the signature architecture is correctly extracted.
 */
class SignatureExplorerTest {

    private static byte[] signedPdfBytes;
    private static byte[] unsignedPdfBytes;

    @BeforeAll
    static void setup() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        unsignedPdfBytes = createPdfWithUnsignedField();
        signedPdfBytes = createSignedPdf();
    }

    @Test
    void shouldExploreUnsignedField() throws IOException {
        SignatureExplorer explorer = new SignatureExplorer();
        SignatureExplorer.ExplorationResult result = explorer.explore(unsignedPdfBytes);

        assertThat(result.getTotalFields()).isEqualTo(1);
        assertThat(result.getFields()).hasSize(1);

        SignatureExplorer.FieldInfo field = result.getFields().get(0);
        assertThat(field.getFieldName()).isEqualTo("Signature1");
        assertThat(field.isSigned()).isFalse();
        assertThat(field.getFilter()).isNull();
        assertThat(field.getSubFilter()).isNull();
    }

    @Test
    void shouldExtractSignatureDictKeys() throws IOException {
        SignatureExplorer explorer = new SignatureExplorer();
        SignatureExplorer.ExplorationResult result = explorer.explore(signedPdfBytes);

        assertThat(result.getTotalFields()).isEqualTo(1);
        assertThat(result.getSigFlags()).isGreaterThan(0);
        assertThat(result.isSignaturesExist()).isTrue();
        assertThat(result.isAppendOnly()).isTrue();

        SignatureExplorer.FieldInfo field = result.getFields().get(0);
        assertThat(field.isSigned()).isTrue();
        assertThat(field.getFilter()).isEqualTo("Adobe.PPKLite");
        assertThat(field.getSubFilter()).isEqualTo("adbe.pkcs7.detached");
        assertThat(field.getByteRange()).hasSize(4);
        assertThat(field.getContentsLength()).isGreaterThan(0);
        assertThat(field.getSignerName()).isEqualTo("Ch12 Test Signer");
        assertThat(field.getReason()).isEqualTo("Testing Chapter 12");
    }

    @Test
    void shouldExtractCmsDetails() throws IOException {
        SignatureExplorer explorer = new SignatureExplorer();
        SignatureExplorer.ExplorationResult result = explorer.explore(signedPdfBytes);

        SignatureExplorer.FieldInfo field = result.getFields().get(0);
        assertThat(field.getDigestAlgorithmOid()).isNotNull();
        assertThat(field.getSignatureAlgorithmOid()).isNotNull();
        assertThat(field.getCertSubjectDN()).contains("Ch12 Test");
        assertThat(field.getCertIssuerDN()).contains("Ch12 Test");
        assertThat(field.getCertChainLength()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldDetectNoSignaturesInBlankPdf() throws IOException {
        byte[] blankPdf;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            blankPdf = baos.toByteArray();
        }

        SignatureExplorer explorer = new SignatureExplorer();
        SignatureExplorer.ExplorationResult result = explorer.explore(blankPdf);
        assertThat(result.getTotalFields()).isZero();
    }

    // ── Test PDF Generators ─────────────────────────────────────────────────

    private static byte[] createPdfWithUnsignedField() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);

            PDSignatureField sigField = new PDSignatureField(acroForm);
            sigField.setPartialName("Signature1");
            acroForm.setFields(Collections.singletonList(sigField));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createSignedPdf() throws Exception {
        // 1. Generate a test key pair and self-signed certificate
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=Ch12 Test Signer, O=PDFalyzer Book, C=AT");
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 365L * 24 * 60 * 60 * 1000);

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(1), notBefore, notAfter,
                subject, keyPair.getPublic());

        ContentSigner certSigner = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        X509CertificateHolder certHolder = certBuilder.build(certSigner);
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(certHolder);

        // 2. Create a blank PDF first, then reload and sign
        //    (PDFBox 3.x requires saveIncremental on a loaded document)
        byte[] blankPdf;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream tmp = new ByteArrayOutputStream();
            doc.save(tmp);
            blankPdf = tmp.toByteArray();
        }

        try (PDDocument doc = Loader.loadPDF(blankPdf)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName("Ch12 Test Signer");
            signature.setReason("Testing Chapter 12");
            signature.setLocation("Vienna");
            signature.setSignDate(Calendar.getInstance());

            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(0x4000);

            // SignatureInterface: hash + CMS construction
            SignatureInterface sigInterface = content -> {
                try {
                    byte[] data = content.readAllBytes();

                    CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
                    gen.addSignerInfoGenerator(
                            new JcaSignerInfoGeneratorBuilder(
                                    new JcaDigestCalculatorProviderBuilder()
                                            .setProvider("BC").build())
                                    .build(new JcaContentSignerBuilder("SHA256withRSA")
                                            .setProvider("BC").build(keyPair.getPrivate()), cert));
                    gen.addCertificate(certHolder);

                    CMSSignedData signedData = gen.generate(
                            new CMSProcessableByteArray(data), false);
                    return signedData.getEncoded();
                } catch (Exception e) {
                    throw new IOException("CMS signing failed", e);
                }
            };

            doc.addSignature(signature, sigInterface, options);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.saveIncremental(baos);
            return baos.toByteArray();
        }
    }
}
