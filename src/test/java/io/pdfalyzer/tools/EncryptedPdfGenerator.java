package io.pdfalyzer.tools;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Generates sample PDFs with various encryption configurations.
 * Run via {@link EncryptedPdfGeneratorTest} to regenerate the files under
 * {@code src/main/resources/sample-pdfs/}.
 *
 * <h3>Generated files</h3>
 * <ul>
 *   <li>{@code sample-encrypted-rc4-40.pdf}   – RC4-40, user+owner password</li>
 *   <li>{@code sample-encrypted-rc4-128.pdf}  – RC4-128, user+owner password</li>
 *   <li>{@code sample-encrypted-aes-128.pdf}  – AES-128, user+owner password</li>
 *   <li>{@code sample-encrypted-aes-256.pdf}  – AES-256, user+owner password</li>
 *   <li>{@code sample-encrypted-owner-only.pdf} – AES-128, empty user password (read-only restrictions)</li>
 * </ul>
 */
public class EncryptedPdfGenerator {

    /** User password used in all password-protected samples. */
    public static final String USER_PASSWORD  = "user123";
    /** Owner password used in all password-protected samples. */
    public static final String OWNER_PASSWORD = "owner456";

    // ── entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get("src", "main", "resources", "sample-pdfs");
        Files.createDirectories(outDir);
        generateAll(outDir);
        System.out.println("Generated encrypted sample PDFs in " + outDir.toAbsolutePath());
    }

    public static void generateAll(Path dir) throws IOException {
        writeFile(dir.resolve("sample-encrypted-rc4-40.pdf"),
                buildEncrypted("RC4-40 encrypted PDF", USER_PASSWORD, OWNER_PASSWORD, 40, false, true));
        writeFile(dir.resolve("sample-encrypted-rc4-128.pdf"),
                buildEncrypted("RC4-128 encrypted PDF", USER_PASSWORD, OWNER_PASSWORD, 128, false, true));
        writeFile(dir.resolve("sample-encrypted-aes-128.pdf"),
                buildEncrypted("AES-128 encrypted PDF", USER_PASSWORD, OWNER_PASSWORD, 128, true, true));
        writeFile(dir.resolve("sample-encrypted-aes-256.pdf"),
                buildEncrypted("AES-256 encrypted PDF", USER_PASSWORD, OWNER_PASSWORD, 256, true, true));
        writeFile(dir.resolve("sample-encrypted-owner-only.pdf"),
                buildOwnerOnlyEncrypted("Owner-restricted PDF (no user password)", OWNER_PASSWORD));
    }

    // ── builders ──────────────────────────────────────────────────────────────

    /**
     * Creates a PDF encrypted with the specified algorithm.
     *
     * @param label      text rendered on the first page
     * @param userPw     user (open) password; non-empty means a password is
     *                   required to open the file
     * @param ownerPw    owner password for permissions management
     * @param keyBits    40, 128 or 256
     * @param preferAes  true → AES; false → RC4 (only relevant for 128-bit)
     * @param allowMod   whether modification is permitted in the permissions
     */
    public static byte[] buildEncrypted(String label, String userPw, String ownerPw,
                                         int keyBits, boolean preferAes,
                                         boolean allowMod) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, label, "User pw: " + userPw + "  Owner pw: " + ownerPw,
                    "Key bits: " + keyBits + "  AES: " + preferAes);

            AccessPermission perm = new AccessPermission();
            perm.setCanModify(allowMod);
            perm.setCanPrint(true);
            perm.setCanExtractContent(true);

            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(ownerPw, userPw, perm);
            policy.setEncryptionKeyLength(keyBits);
            policy.setPreferAES(preferAes);
            doc.protect(policy);

            return toBytes(doc);
        }
    }

    /**
     * Creates a PDF where only an owner password is set (empty user password).
     * The file opens without a password but reports limited modification rights.
     */
    public static byte[] buildOwnerOnlyEncrypted(String label, String ownerPw)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, label,
                    "No user password – opens without a password",
                    "Owner pw: " + ownerPw + "  Modification: restricted");

            AccessPermission perm = new AccessPermission();
            perm.setCanModify(false);          // restrict editing
            perm.setCanPrint(true);
            perm.setCanExtractContent(false);  // also restrict copy/paste

            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(ownerPw, "", perm);
            policy.setEncryptionKeyLength(128);
            policy.setPreferAES(true);
            doc.protect(policy);

            return toBytes(doc);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void addPage(PDDocument doc, String title,
                                 String line2, String line3) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 18);
            cs.newLineAtOffset(60, 750);
            cs.showText(title);
            cs.setFont(mono, 12);
            cs.newLineAtOffset(0, -40);
            cs.showText(line2);
            cs.newLineAtOffset(0, -20);
            cs.showText(line3);
            cs.endText();
        }
    }

    private static byte[] toBytes(PDDocument doc) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static void writeFile(Path path, byte[] data) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(data);
        }
        System.out.println("  Wrote " + path.getFileName() + " (" + data.length + " bytes)");
    }
}
