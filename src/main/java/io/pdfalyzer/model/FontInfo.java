package io.pdfalyzer.model;

import java.util.ArrayList;
import java.util.List;

public class FontInfo {
    private String fontName;
    private String fontType;
    private String encoding;
    private boolean embedded;
    private boolean subset;
    private int glyphCount;
    private List<String> issues = new ArrayList<>();
    private int pageIndex;
    private String objectId;

    public FontInfo() {}

    public String getFontName() { return fontName; }
    public void setFontName(String fontName) { this.fontName = fontName; }

    public String getFontType() { return fontType; }
    public void setFontType(String fontType) { this.fontType = fontType; }

    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }

    public boolean isEmbedded() { return embedded; }
    public void setEmbedded(boolean embedded) { this.embedded = embedded; }

    public boolean isSubset() { return subset; }
    public void setSubset(boolean subset) { this.subset = subset; }

    public int getGlyphCount() { return glyphCount; }
    public void setGlyphCount(int glyphCount) { this.glyphCount = glyphCount; }

    public List<String> getIssues() { return issues; }
    public void setIssues(List<String> issues) { this.issues = issues; }

    public void addIssue(String issue) { this.issues.add(issue); }

    public int getPageIndex() { return pageIndex; }
    public void setPageIndex(int pageIndex) { this.pageIndex = pageIndex; }

    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
}
