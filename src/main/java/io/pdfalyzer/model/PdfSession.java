package io.pdfalyzer.model;

public class PdfSession {
    private String id;
    private String filename;
    private byte[] pdfBytes;
    private PdfNode treeRoot;
    private PdfNode rawCosTree;
    private int pageCount;
    private long lastAccessTime;

    public PdfSession() {}

    public PdfSession(String id, String filename, byte[] pdfBytes) {
        this.id = id;
        this.filename = filename;
        this.pdfBytes = pdfBytes;
        this.lastAccessTime = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public byte[] getPdfBytes() { return pdfBytes; }
    public void setPdfBytes(byte[] pdfBytes) { this.pdfBytes = pdfBytes; }

    public PdfNode getTreeRoot() { return treeRoot; }
    public void setTreeRoot(PdfNode treeRoot) { this.treeRoot = treeRoot; }

    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }

    public long getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(long lastAccessTime) { this.lastAccessTime = lastAccessTime; }

    public PdfNode getRawCosTree() { return rawCosTree; }
    public void setRawCosTree(PdfNode rawCosTree) { this.rawCosTree = rawCosTree; }

    public void touch() {
        this.lastAccessTime = System.currentTimeMillis();
    }
}
