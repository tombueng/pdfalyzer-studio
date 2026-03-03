package io.pdfalyzer.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FontDiagnostics {
    @Getter
    @Setter
    private int totalFonts;
    @Getter
    @Setter
    private int fontsWithIssues;
    @Getter
    @Setter
    private int fontsWithMissingGlyphs;
    @Getter
    @Setter
    private int fontsWithEncodingProblems;
    @Getter
    @Setter
    private List<FontDiagnosticsEntry> fonts = new ArrayList<>();

    public static class FontDiagnosticsEntry {
        @Getter
        @Setter
        private String fontName;
        @Getter
        @Setter
        private String fontType;
        @Getter
        @Setter
        private String encoding;
        @Getter
        @Setter
        private boolean embedded;
        @Getter
        @Setter
        private boolean subset;
        @Getter
        @Setter
        private int objectNumber = -1;
        @Getter
        @Setter
        private int generationNumber = -1;
        @Getter
        @Setter
        private int glyphCount;
        @Getter
        @Setter
        private int distinctUsedCodes;
        @Getter
        @Setter
        private int mappedUsedCodes;
        @Getter
        @Setter
        private int unmappedUsedCodes;
        @Getter
        @Setter
        private int unencodableUsedChars;
        @Getter
        @Setter
        private List<Integer> pagesUsed = new ArrayList<>();
        @Getter
        @Setter
        private List<String> usageContexts = new ArrayList<>();
        @Getter
        @Setter
        private boolean subsetComplete;
        @Getter
        @Setter
        private List<String> issues = new ArrayList<>();
        @Getter
        @Setter
        private String fixSuggestion;
    }

    public static class FontDiagnosticsDetail {
        @Getter
        @Setter
        private FontDiagnosticsEntry font;
        @Getter
        @Setter
        private EncodingDiagnostics encoding = new EncodingDiagnostics();
        @Getter
        @Setter
        private List<GlyphMapping> glyphMappings = new ArrayList<>();
        @Getter
        @Setter
        private List<GlyphMapping> missingUsedGlyphMappings = new ArrayList<>();
        @Getter
        @Setter
        private List<UsedCharacterIssue> usedCharacterIssues = new ArrayList<>();
        @Getter
        @Setter
        private Map<String, String> fontDictionary = new LinkedHashMap<>();
    }

    public static class EncodingDiagnostics {
        @Getter
        @Setter
        private boolean hasToUnicode;
        @Getter
        @Setter
        private String encodingObject;
        @Getter
        @Setter
        private String toUnicodeObject;
        @Getter
        @Setter
        private String subtype;
        @Getter
        @Setter
        private String baseFont;
        @Getter
        @Setter
        private String descendantFont;
        @Getter
        @Setter
        private String cmapName;
        @Getter
        @Setter
        private String descendantSubtype;
    }

    public enum RenderStatus {
        OK, GLYPH_MISSING, NOT_EMBEDDED, UNKNOWN;
        public String label() {
            return switch (this) {
                case OK -> "OK";
                case GLYPH_MISSING -> "Glyph missing from font";
                case NOT_EMBEDDED -> "Font not embedded";
                default -> "Unknown";
            };
        }
    }

    public enum ExtractionStatus {
        OK, NO_UNICODE_MAPPING, ENCODING_MISMATCH, UNKNOWN;
        public String label() {
            return switch (this) {
                case OK -> "OK";
                case NO_UNICODE_MAPPING -> "No Unicode mapping";
                case ENCODING_MISMATCH -> "Encoding mismatch";
                default -> "Unknown";
            };
        }
    }

    public enum DiagnosticStatus {
        OK, NO_UNICODE_MAPPING, GLYPH_NOT_IN_FONT, ENCODING_MISMATCH, UNKNOWN;
        public String label() {
            return switch (this) {
                case OK -> "OK";
                case NO_UNICODE_MAPPING -> "No Unicode mapping";
                case GLYPH_NOT_IN_FONT -> "Glyph not in font";
                case ENCODING_MISMATCH -> "Encoding mismatch";
                default -> "Unknown";
            };
        }
    }

    public static class GlyphMapping {
        @Getter
        @Setter
        private int code;
        @Getter
        @Setter
        private String unicode;
        @Getter
        @Setter
        private String unicodeHex;
        @Getter
        @Setter
        private Integer width;
        @Getter
        @Setter
        private int usedCount;
        @Getter
        @Setter
        private boolean mapped;
        @Getter
        @Setter
        private String glyphName;
        @Getter
        @Setter
        private boolean glyphPresent;
        @Getter
        @Setter
        private DiagnosticStatus diagnosticStatus = DiagnosticStatus.UNKNOWN;
        @Getter
        @Setter
        private RenderStatus renderStatus = RenderStatus.UNKNOWN;
        @Getter
        @Setter
        private ExtractionStatus extractionStatus = ExtractionStatus.UNKNOWN;
    }

    public static class UsedCharacterIssue {
        @Getter
        @Setter
        private String character;
        @Getter
        @Setter
        private String unicodeHex;
        @Getter
        @Setter
        private int count;
        @Getter
        @Setter
        private String issue;
    }
}