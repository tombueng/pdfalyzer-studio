package io.pdfalyzer.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FontDiagnostics {
    private int totalFonts;
    private int fontsWithIssues;
    private int fontsWithMissingGlyphs;
    private int fontsWithEncodingProblems;
    private List<FontDiagnosticsEntry> fonts = new ArrayList<>();

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
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
        private boolean subsetComplete;
        private List<String> issues = new ArrayList<>();
        private String fixSuggestion;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FontDiagnosticsDetail {
        private FontDiagnosticsEntry font;
        private EncodingDiagnostics encoding = new EncodingDiagnostics();
        private List<GlyphMapping> glyphMappings = new ArrayList<>();
        private List<GlyphMapping> missingUsedGlyphMappings = new ArrayList<>();
        private List<UsedCharacterIssue> usedCharacterIssues = new ArrayList<>();
        private Map<String, String> fontDictionary = new LinkedHashMap<>();
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class EncodingDiagnostics {
        private boolean hasToUnicode;
        private String encodingObject;
        private String toUnicodeObject;
        private String subtype;
        private String baseFont;
        private String descendantFont;
        private String cmapName;
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

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class GlyphMapping {
        private int code;
        private String unicode;
        private String unicodeHex;
        private Integer width;
        private int usedCount;
        private boolean mapped;
        private String glyphName;
        private boolean glyphPresent;
        private DiagnosticStatus diagnosticStatus = DiagnosticStatus.UNKNOWN;
        private RenderStatus renderStatus = RenderStatus.UNKNOWN;
        private ExtractionStatus extractionStatus = ExtractionStatus.UNKNOWN;
    }

    @Data
    public static class UsedCharacterIssue {
        private String character;
        private String unicodeHex;
        private int count;
        private String issue;
    }
}