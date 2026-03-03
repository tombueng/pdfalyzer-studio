package io.pdfalyzer.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
public class PdfSession {
    @Getter
    @Setter
    private String id;
    @Getter
    @Setter
    private String filename;
    @Getter
    @Setter
    private byte[] pdfBytes;
    @Getter
    @Setter
    private PdfNode treeRoot;
    @Getter
    @Setter
    private PdfNode rawCosTree;
    @Getter
    @Setter
    private int pageCount;
    @Getter
    @Setter
    private long lastAccessTime;

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
