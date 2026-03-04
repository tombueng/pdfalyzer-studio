package io.pdfalyzer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.PDEncryption;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.EncryptionInfo;
import io.pdfalyzer.model.PdfNode;
import io.pdfalyzer.model.PdfSession;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PdfService {

    private final SessionService sessionService;
    private final PdfStructureParser structureParser;

    public PdfService(SessionService sessionService, PdfStructureParser structureParser) {
        this.sessionService = sessionService;
        this.structureParser = structureParser;
    }

    /**
     * Uploads and attempts to parse a PDF.  If the document requires a user
     * password the session is still created (so the client can call
     * {@link #unlockSession} later) but the tree is left null and
     * {@link EncryptionInfo#isRequiresPassword()} is {@code true}.
     */
    public PdfSession uploadAndParse(String filename, byte[] pdfBytes) throws IOException {
        PdfSession session = sessionService.createSession(filename, pdfBytes);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            EncryptionInfo encInfo = buildEncryptionInfo(doc, "empty-user");
            session.setEncryptionInfo(encInfo);
            session.setTreeRoot(structureParser.buildTree(doc));
            session.setPageCount(doc.getNumberOfPages());
            log.info("Parsed '{}': {} pages, encrypted={}", filename,
                    session.getPageCount(), encInfo.isEncrypted());
        } catch (InvalidPasswordException e) {
            EncryptionInfo encInfo = EncryptionInfo.builder()
                    .encrypted(true)
                    .requiresPassword(true)
                    .canModify(false)
                    .canPrint(false)
                    .canExtractContent(false)
                    .passwordType("none")
                    .build();
            session.setEncryptionInfo(encInfo);
            log.info("Uploaded '{}': requires password", filename);
        }
        return session;
    }

    /**
     * Tries to open a password-protected session using the supplied password.
     * The password is tested as the user password first, then as the owner
     * password.  On success the document is decrypted in memory, the tree is
     * (re-)built, and the session bytes are replaced with an unencrypted copy
     * so that subsequent PDF.js rendering works without a password.
     *
     * @throws IllegalArgumentException if the password is wrong for both roles.
     */
    public PdfSession unlockSession(String sessionId, String password) throws IOException {
        PdfSession session = sessionService.getSession(sessionId);
        byte[] pdfBytes = session.getPdfBytes();

        PDDocument openedDoc = null;
        String passwordType = null;

        // Try as user password
        try {
            openedDoc = Loader.loadPDF(pdfBytes, password);
            passwordType = "user";
        } catch (InvalidPasswordException ignored) {
            // fall through to owner password attempt
        }

        if (openedDoc == null) {
            throw new IllegalArgumentException("Invalid password – not accepted as user or owner password");
        }

        try {
            EncryptionInfo encInfo = buildEncryptionInfo(openedDoc, passwordType);
            session.setEncryptionInfo(encInfo);
            session.setTreeRoot(structureParser.buildTree(openedDoc));
            session.setPageCount(openedDoc.getNumberOfPages());
            session.setRawCosTree(null);

            // Persist an unencrypted copy so PDF.js can render without a password.
            openedDoc.setAllSecurityToBeRemoved(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            openedDoc.save(out);
            session.setPdfBytes(out.toByteArray());

            log.info("Unlocked '{}' as {} password; canModify={}",
                    session.getFilename(), passwordType, encInfo.isCanModify());
        } finally {
            openedDoc.close();
        }

        return session;
    }

    public byte[] getSessionPdfBytes(String sessionId) {
        return sessionService.getSession(sessionId).getPdfBytes();
    }

    public PdfNode getTree(String sessionId) {
        return sessionService.getSession(sessionId).getTreeRoot();
    }

    public PdfSession getSession(String sessionId) {
        return sessionService.getSession(sessionId);
    }

    public List<PdfNode> searchTree(String sessionId, String query) {
        PdfNode root = getTree(sessionId);
        List<PdfNode> results = new ArrayList<>();
        searchNodes(root, query.toLowerCase(), results);
        return results;
    }

    private void searchNodes(PdfNode node, String query, List<PdfNode> results) {
        if (node == null) return;

        boolean matches = false;
        if (node.getName() != null && node.getName().toLowerCase().contains(query)) {
            matches = true;
        }
        if (!matches && node.getProperties() != null) {
            for (String value : node.getProperties().values()) {
                if (value != null && value.toLowerCase().contains(query)) {
                    matches = true;
                    break;
                }
            }
        }
        if (matches) results.add(node);
        for (PdfNode child : node.getChildren()) {
            searchNodes(child, query, results);
        }
    }

    public void updateSessionPdf(String sessionId, byte[] newBytes) throws IOException {
        PdfSession session = sessionService.getSession(sessionId);
        session.setPdfBytes(newBytes);
        try (PDDocument doc = Loader.loadPDF(newBytes)) {
            session.setTreeRoot(structureParser.buildTree(doc));
            session.setPageCount(doc.getNumberOfPages());
        }
        session.setRawCosTree(null);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static EncryptionInfo buildEncryptionInfo(PDDocument doc, String passwordType) {
        if (!doc.isEncrypted()) {
            return EncryptionInfo.builder()
                    .encrypted(false)
                    .canModify(true)
                    .canPrint(true)
                    .canExtractContent(true)
                    .passwordType("none")
                    .build();
        }

        PDEncryption enc = doc.getEncryption();
        AccessPermission perm = doc.getCurrentAccessPermission();

        int v = enc.getVersion();
        // enc.getLength() returns the key size in bits (per PDF spec /Length entry).
        int keyBits;
        String algorithm;
        if (v == 5) {
            keyBits = 256;
            algorithm = "AES-256";
        } else if (v == 4) {
            keyBits = 128;
            algorithm = "AES-128";
        } else if (v >= 2) {
            keyBits = enc.getLength(); // already in bits
            algorithm = "RC4-" + keyBits;
        } else {
            keyBits = 40;
            algorithm = "RC4-40";
        }

        return EncryptionInfo.builder()
                .encrypted(true)
                .requiresPassword(false)
                .canModify(perm.canModify())
                .canPrint(perm.canPrint())
                .canExtractContent(perm.canExtractContent())
                .algorithm(algorithm)
                .version(v)
                .revision(enc.getRevision())
                .keyBits(keyBits)
                .passwordType(passwordType)
                .build();
    }
}
