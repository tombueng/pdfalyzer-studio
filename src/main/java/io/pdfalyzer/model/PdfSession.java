package io.pdfalyzer.model;

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

    public PdfSession(String id, String filename, byte[] pdfBytes) {
        this.id = id;
        this.filename = filename;
        this.pdfBytes = pdfBytes;
        this.lastAccessTime = System.currentTimeMillis();
    }

    public void touch() {
        this.lastAccessTime = System.currentTimeMillis();
    }
}
