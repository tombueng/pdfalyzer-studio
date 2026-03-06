package io.pdfalyzer.model;

import java.util.concurrent.ConcurrentHashMap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class PdfSession {
    private String id;
    private String filename;
    private byte[] pdfBytes;
    private PdfNode treeRoot;
    private PdfNode rawCosTree;
    private int pageCount;
    private long lastAccessTime;
    private EncryptionInfo encryptionInfo;
    /** The password the user supplied to unlock this session (null if never password-protected). */
    private String unlockedWithPassword;
    private boolean hasSignatureFields;
    /** Uploaded key material for signing, keyed by sessionKeyId. Cleared on session expiry. */
    @Builder.Default
    private ConcurrentHashMap<String, SigningKeyMaterial> signingKeyMaterials = new ConcurrentHashMap<>();

    public PdfSession(String id, String filename, byte[] pdfBytes) {
        this.id = id;
        this.filename = filename;
        this.pdfBytes = pdfBytes;
        this.lastAccessTime = System.currentTimeMillis();
        this.signingKeyMaterials = new ConcurrentHashMap<>();
    }

    public void touch() {
        this.lastAccessTime = System.currentTimeMillis();
    }
}
