package io.pdfalyzer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.pdfalyzer.model.EncryptionInfo;
import io.pdfalyzer.model.PdfSession;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests for encrypted PDF handling in {@link PdfService} and
 * {@link PdfService#buildEncryptionInfo(PDDocument, String)}.
 *
 * Uses pre-generated sample PDFs from {@code src/main/resources/sample-pdfs/}.
 * Regenerate them with: {@code mvn test -Dtest=EncryptedPdfGeneratorTest -Dgenerate.samples=true}
 */
class EncryptedPdfServiceTest {

    static final String USER_PASSWORD  = "user123";
    static final String OWNER_PASSWORD = "owner456";

    private static final Path SAMPLE_DIR = Path.of("src/main/resources/sample-pdfs");

    private PdfService pdfService;

    @BeforeEach
    void setUp() {
        SessionService sessionService = new SessionService();
        CosNodeBuilder cosBuilder = new CosNodeBuilder();
        PageResourceBuilder pageResourceBuilder = new PageResourceBuilder(cosBuilder);
        AcroFormTreeBuilder acroFormBuilder = new AcroFormTreeBuilder(cosBuilder);
        DocumentStructureTreeBuilder docStructureBuilder = new DocumentStructureTreeBuilder(cosBuilder);
        SemanticTreeBuilder semanticBuilder = new SemanticTreeBuilder(
                cosBuilder, pageResourceBuilder, acroFormBuilder, docStructureBuilder);
        PdfStructureParser parser = new PdfStructureParser(semanticBuilder, cosBuilder);
        pdfService = new PdfService(sessionService, parser);
    }

    // ── uploadAndParse with unencrypted PDF ───────────────────────────────────

    @Test
    void uploadAndParse_unencrypted_setsEncryptionInfoNotEncrypted() throws Exception {
        byte[] plain = buildPlainPdf();

        PdfSession session = pdfService.uploadAndParse("plain.pdf", plain);

        assertThat(session.getEncryptionInfo()).isNotNull();
        assertThat(session.getEncryptionInfo().isEncrypted()).isFalse();
        assertThat(session.getEncryptionInfo().isRequiresPassword()).isFalse();
        assertThat(session.getEncryptionInfo().isCanModify()).isTrue();
        assertThat(session.getTreeRoot()).isNotNull();
        assertThat(session.getPageCount()).isGreaterThan(0);
    }

    // ── uploadAndParse with owner-only encryption (no user pw) ───────────────

    @Test
    void uploadAndParse_ownerOnlyEncryption_opensWithoutPassword() throws Exception {
        byte[] pdf = Files.readAllBytes(SAMPLE_DIR.resolve("sample-encrypted-owner-only.pdf"));

        PdfSession session = pdfService.uploadAndParse("owner-only.pdf", pdf);

        assertThat(session.getEncryptionInfo()).isNotNull();
        assertThat(session.getEncryptionInfo().isEncrypted()).isTrue();
        assertThat(session.getEncryptionInfo().isRequiresPassword()).isFalse();
        assertThat(session.getEncryptionInfo().isCanModify()).isFalse();
        assertThat(session.getTreeRoot()).isNotNull();
    }

    // ── uploadAndParse with user-password PDF ────────────────────────────────

    @Test
    void uploadAndParse_userPasswordRequired_setsRequiresPassword() throws Exception {
        byte[] pdf = Files.readAllBytes(SAMPLE_DIR.resolve("sample-encrypted-aes-128.pdf"));

        PdfSession session = pdfService.uploadAndParse("aes128.pdf", pdf);

        assertThat(session.getEncryptionInfo()).isNotNull();
        assertThat(session.getEncryptionInfo().isEncrypted()).isTrue();
        assertThat(session.getEncryptionInfo().isRequiresPassword()).isTrue();
        assertThat(session.getTreeRoot()).isNull();
        assertThat(session.getPdfBytes()).isEqualTo(pdf);
    }

    // ── unlockSession ─────────────────────────────────────────────────────────

    @Test
    void unlockSession_correctUserPassword_parsesTree() throws Exception {
        byte[] pdf = Files.readAllBytes(SAMPLE_DIR.resolve("sample-encrypted-aes-256.pdf"));

        PdfSession session = pdfService.uploadAndParse("aes256.pdf", pdf);
        assertThat(session.getEncryptionInfo().isRequiresPassword()).isTrue();

        PdfSession unlocked = pdfService.unlockSession(session.getId(), USER_PASSWORD);

        assertThat(unlocked.getTreeRoot()).isNotNull();
        assertThat(unlocked.getPageCount()).isGreaterThan(0);
        assertThat(unlocked.getEncryptionInfo().isRequiresPassword()).isFalse();
        assertThat(unlocked.getEncryptionInfo().isEncrypted()).isTrue();
        assertThat(unlocked.getEncryptionInfo().getAlgorithm()).isEqualTo("AES-256");
        assertThat(unlocked.getEncryptionInfo().getPasswordType()).isEqualTo("user");

        try (PDDocument check = Loader.loadPDF(unlocked.getPdfBytes())) {
            assertThat(check.isEncrypted()).isFalse();
        }
    }

    @Test
    void unlockSession_wrongPassword_throwsIllegalArgument() throws Exception {
        byte[] pdf = Files.readAllBytes(SAMPLE_DIR.resolve("sample-encrypted-rc4-128.pdf"));

        PdfSession session = pdfService.uploadAndParse("rc4-128.pdf", pdf);
        String sid = session.getId();

        assertThatThrownBy(() -> pdfService.unlockSession(sid, "wrongpassword"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid password");
    }

    // ── buildEncryptionInfo helper ────────────────────────────────────────────

    @Test
    void buildEncryptionInfo_aes256_returnsCorrectAlgorithm() throws Exception {
        byte[] pdf = Files.readAllBytes(SAMPLE_DIR.resolve("sample-encrypted-aes-256.pdf"));

        try (PDDocument doc = Loader.loadPDF(pdf, USER_PASSWORD)) {
            EncryptionInfo info = PdfService.buildEncryptionInfo(doc, "user");
            assertThat(info.isEncrypted()).isTrue();
            assertThat(info.getAlgorithm()).isEqualTo("AES-256");
            assertThat(info.getKeyBits()).isEqualTo(256);
            assertThat(info.getVersion()).isEqualTo(5);
            assertThat(info.getPasswordType()).isEqualTo("user");
        }
    }

    @Test
    void buildEncryptionInfo_rc4_40_returnsCorrectAlgorithm() throws Exception {
        byte[] pdf = Files.readAllBytes(SAMPLE_DIR.resolve("sample-encrypted-rc4-40.pdf"));

        try (PDDocument doc = Loader.loadPDF(pdf, USER_PASSWORD)) {
            EncryptionInfo info = PdfService.buildEncryptionInfo(doc, "user");
            assertThat(info.isEncrypted()).isTrue();
            assertThat(info.getAlgorithm()).startsWith("RC4");
            assertThat(info.getKeyBits()).isEqualTo(40);
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private byte[] buildPlainPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
