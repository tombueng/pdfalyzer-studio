package io.pdfalyzer.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FontDiagnostics {
    private int totalFonts;
    private int fontsWithIssues;
    private int fontsWithMissingGlyphs;
    private int fontsWithEncodingProblems;
    private List<FontDiagnosticsEntry> fonts = new ArrayList<>();

    public int getTotalFonts() { return totalFonts; }
    public void setTotalFonts(int totalFonts) { this.totalFonts = totalFonts; }

    public int getFontsWithIssues() { return fontsWithIssues; }
    public void setFontsWithIssues(int fontsWithIssues) { this.fontsWithIssues = fontsWithIssues; }

    public int getFontsWithMissingGlyphs() { return fontsWithMissingGlyphs; }
    public void setFontsWithMissingGlyphs(int fontsWithMissingGlyphs) { this.fontsWithMissingGlyphs = fontsWithMissingGlyphs; }

    public int getFontsWithEncodingProblems() { return fontsWithEncodingProblems; }
    public void setFontsWithEncodingProblems(int fontsWithEncodingProblems) { this.fontsWithEncodingProblems = fontsWithEncodingProblems; }

    public List<FontDiagnosticsEntry> getFonts() { return fonts; }
    public void setFonts(List<FontDiagnosticsEntry> fonts) { this.fonts = fonts; }

    public static class FontDiagnosticsEntry {
        private String fontName;
        private String fontType;
        private String encoding;
        private boolean embedded;
        private boolean subset;
        private int objectNumber = -1;
        private int generationNumber = -1;
        private int glyphCount;
        private int distinctUsedCodes;
        private int mappedUsedCodes;
        private int unmappedUsedCodes;
        private int unencodableUsedChars;
        private List<Integer> pagesUsed = new ArrayList<>();
        private List<String> usageContexts = new ArrayList<>();
        private List<String> issues = new ArrayList<>();
        private String fixSuggestion;

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

        public int getObjectNumber() { return objectNumber; }
        public void setObjectNumber(int objectNumber) { this.objectNumber = objectNumber; }

        public int getGenerationNumber() { return generationNumber; }
        public void setGenerationNumber(int generationNumber) { this.generationNumber = generationNumber; }

        public int getGlyphCount() { return glyphCount; }
        public void setGlyphCount(int glyphCount) { this.glyphCount = glyphCount; }

        public int getDistinctUsedCodes() { return distinctUsedCodes; }
        public void setDistinctUsedCodes(int distinctUsedCodes) { this.distinctUsedCodes = distinctUsedCodes; }

        public int getMappedUsedCodes() { return mappedUsedCodes; }
        public void setMappedUsedCodes(int mappedUsedCodes) { this.mappedUsedCodes = mappedUsedCodes; }

        public int getUnmappedUsedCodes() { return unmappedUsedCodes; }
        public void setUnmappedUsedCodes(int unmappedUsedCodes) { this.unmappedUsedCodes = unmappedUsedCodes; }

        public int getUnencodableUsedChars() { return unencodableUsedChars; }
        public void setUnencodableUsedChars(int unencodableUsedChars) { this.unencodableUsedChars = unencodableUsedChars; }

        public List<Integer> getPagesUsed() { return pagesUsed; }
        public void setPagesUsed(List<Integer> pagesUsed) { this.pagesUsed = pagesUsed; }

        public List<String> getUsageContexts() { return usageContexts; }
        public void setUsageContexts(List<String> usageContexts) { this.usageContexts = usageContexts; }

        public List<String> getIssues() { return issues; }
        public void setIssues(List<String> issues) { this.issues = issues; }

        public String getFixSuggestion() { return fixSuggestion; }
        public void setFixSuggestion(String fixSuggestion) { this.fixSuggestion = fixSuggestion; }
    }

    public static class FontDiagnosticsDetail {
        private FontDiagnosticsEntry font;
        private EncodingDiagnostics encoding = new EncodingDiagnostics();
        private List<GlyphMapping> glyphMappings = new ArrayList<>();
        private List<GlyphMapping> missingUsedGlyphMappings = new ArrayList<>();
        private List<UsedCharacterIssue> usedCharacterIssues = new ArrayList<>();
        private Map<String, String> fontDictionary = new LinkedHashMap<>();

        public FontDiagnosticsEntry getFont() { return font; }
        public void setFont(FontDiagnosticsEntry font) { this.font = font; }

        public EncodingDiagnostics getEncoding() { return encoding; }
        public void setEncoding(EncodingDiagnostics encoding) { this.encoding = encoding; }

        public List<GlyphMapping> getGlyphMappings() { return glyphMappings; }
        public void setGlyphMappings(List<GlyphMapping> glyphMappings) { this.glyphMappings = glyphMappings; }

        public List<GlyphMapping> getMissingUsedGlyphMappings() { return missingUsedGlyphMappings; }
        public void setMissingUsedGlyphMappings(List<GlyphMapping> missingUsedGlyphMappings) { this.missingUsedGlyphMappings = missingUsedGlyphMappings; }

        public List<UsedCharacterIssue> getUsedCharacterIssues() { return usedCharacterIssues; }
        public void setUsedCharacterIssues(List<UsedCharacterIssue> usedCharacterIssues) { this.usedCharacterIssues = usedCharacterIssues; }

        public Map<String, String> getFontDictionary() { return fontDictionary; }
        public void setFontDictionary(Map<String, String> fontDictionary) { this.fontDictionary = fontDictionary; }
    }

    public static class EncodingDiagnostics {
        private boolean hasToUnicode;
        private String encodingObject;
        private String toUnicodeObject;
        private String subtype;
        private String baseFont;
        private String descendantFont;

        public boolean isHasToUnicode() { return hasToUnicode; }
        public void setHasToUnicode(boolean hasToUnicode) { this.hasToUnicode = hasToUnicode; }

        public String getEncodingObject() { return encodingObject; }
        public void setEncodingObject(String encodingObject) { this.encodingObject = encodingObject; }

        public String getToUnicodeObject() { return toUnicodeObject; }
        public void setToUnicodeObject(String toUnicodeObject) { this.toUnicodeObject = toUnicodeObject; }

        public String getSubtype() { return subtype; }
        public void setSubtype(String subtype) { this.subtype = subtype; }

        public String getBaseFont() { return baseFont; }
        public void setBaseFont(String baseFont) { this.baseFont = baseFont; }

        public String getDescendantFont() { return descendantFont; }
        public void setDescendantFont(String descendantFont) { this.descendantFont = descendantFont; }
    }

    public static class GlyphMapping {
        private int code;
        private String unicode;
        private String unicodeHex;
        private Integer width;
        private int usedCount;
        private boolean mapped;

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }

        public String getUnicode() { return unicode; }
        public void setUnicode(String unicode) { this.unicode = unicode; }

        public String getUnicodeHex() { return unicodeHex; }
        public void setUnicodeHex(String unicodeHex) { this.unicodeHex = unicodeHex; }

        public Integer getWidth() { return width; }
        public void setWidth(Integer width) { this.width = width; }

        public int getUsedCount() { return usedCount; }
        public void setUsedCount(int usedCount) { this.usedCount = usedCount; }

        public boolean isMapped() { return mapped; }
        public void setMapped(boolean mapped) { this.mapped = mapped; }
    }

    public static class UsedCharacterIssue {
        private String character;
        private String unicodeHex;
        private int count;
        private String issue;

        public String getCharacter() { return character; }
        public void setCharacter(String character) { this.character = character; }

        public String getUnicodeHex() { return unicodeHex; }
        public void setUnicodeHex(String unicodeHex) { this.unicodeHex = unicodeHex; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public String getIssue() { return issue; }
        public void setIssue(String issue) { this.issue = issue; }
    }
}