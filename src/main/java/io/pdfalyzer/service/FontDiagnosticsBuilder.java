package io.pdfalyzer.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.springframework.stereotype.Component;

import io.pdfalyzer.model.FontDiagnostics;
import io.pdfalyzer.service.FontCollectionHelper.FontRefAggregate;
import io.pdfalyzer.service.FontCollectionHelper.UsageStats;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds font diagnostics entries, encoding diagnostics, and glyph mappings.
 * Used by {@link FontInspectorService}.
 */
@Component
@Slf4j
public class FontDiagnosticsBuilder {

    FontDiagnostics.FontDiagnosticsEntry buildDiagnosticsEntry(FontRefAggregate aggregate, UsageStats stats) {
        FontDiagnostics.FontDiagnosticsEntry entry = new FontDiagnostics.FontDiagnosticsEntry();
        PDFont font = aggregate.font;
        boolean forceUnmapped = hasExplicitEmptyToUnicodeMap(font);

        entry.setFontName(safeFontName(font));
        entry.setFontType(font.getClass().getSimpleName().replace("PD", ""));
        entry.setEmbedded(font.isEmbedded());
        entry.setSubset(isSubset(font));
        entry.setEncoding(readEncoding(font));
        if (aggregate.key != null) {
            entry.setObjectNumber((int) aggregate.key.getNumber());
            entry.setGenerationNumber((int) aggregate.key.getGeneration());
        }
        entry.setGlyphCount(stats.glyphCount);
        entry.setDistinctUsedCodes(stats.usedCodes.size());

        int mappedUsedCodes = 0, unmappedUsedCodes = 0;
        for (int code : stats.usedCodes.keySet()) {
            String unicode = safeToUnicode(font, code, forceUnmapped);
            if (unicode == null || unicode.isEmpty()) unmappedUsedCodes++;
            else mappedUsedCodes++;
        }
        entry.setMappedUsedCodes(mappedUsedCodes);
        entry.setUnmappedUsedCodes(unmappedUsedCodes);
        entry.setUnencodableUsedChars(unmappedUsedCodes);

        entry.setPagesUsed(new ArrayList<>(new TreeSet<>(aggregate.pages)));
        entry.setUsageContexts(new ArrayList<>(new TreeSet<>(aggregate.contexts)));

        boolean allUsedGlyphsPresent = true;
        for (int code : stats.usedCodes.keySet()) {
            String gName = safeGlyphName(font, code);
            if (!isGlyphPresent(font, code, gName)) { allUsedGlyphsPresent = false; break; }
        }
        entry.setSubsetComplete(allUsedGlyphsPresent);

        if (!font.isEmbedded()) {
            entry.getIssues().add("Font is not embedded");
            entry.setFixSuggestion("Embed this font or replace with an embedded subset to avoid platform-dependent rendering.");
        }
        if (font instanceof PDType3Font) {
            entry.getIssues().add("Type3 font can cause inconsistent rendering");
            if (entry.getFixSuggestion() == null)
                entry.setFixSuggestion("Prefer embedded TrueType/OpenType fonts for better compatibility.");
        }
        if (entry.getUnmappedUsedCodes() > 0) {
            entry.getIssues().add("Used character codes are missing Unicode mappings: " + entry.getUnmappedUsedCodes());
            if (entry.getFixSuggestion() == null)
                entry.setFixSuggestion("Add or repair ToUnicode CMap for this font.");
        }
        if (entry.getUnencodableUsedChars() > 0) {
            entry.getIssues().add("Used codes without Unicode mapping (missing glyph chars): " + entry.getUnencodableUsedChars());
            if (entry.getFixSuggestion() == null)
                entry.setFixSuggestion("Use a font that covers all required glyphs or split text across fallback fonts.");
        }
        if (stats.positionsWithoutUnicode > 0) {
            entry.getIssues().add("Text positions without Unicode output: " + stats.positionsWithoutUnicode);
            if (entry.getFixSuggestion() == null)
                entry.setFixSuggestion("Verify Encoding and ToUnicode entries for this font.");
        }
        return entry;
    }

    FontDiagnostics.EncodingDiagnostics buildEncodingDiagnostics(PDFont font) {
        FontDiagnostics.EncodingDiagnostics encoding = new FontDiagnostics.EncodingDiagnostics();
        COSDictionary dict = font.getCOSObject();
        encoding.setHasToUnicode(dict.containsKey(COSName.TO_UNICODE));
        encoding.setEncodingObject(summarizeCos(dict.getDictionaryObject(COSName.ENCODING)));
        encoding.setToUnicodeObject(summarizeCos(dict.getDictionaryObject(COSName.TO_UNICODE)));
        encoding.setSubtype(safeName(dict.getCOSName(COSName.SUBTYPE)));
        encoding.setBaseFont(safeName(dict.getCOSName(COSName.BASE_FONT)));

        COSBase encodingEntry = dict.getDictionaryObject(COSName.ENCODING);
        if (encodingEntry instanceof COSName cmapCosName) {
            encoding.setCmapName(cmapCosName.getName());
        } else if (encodingEntry instanceof COSStream) {
            encoding.setCmapName("(embedded CMap stream)");
        }
        if (encoding.getCmapName() == null && font instanceof PDType0Font type0Font) {
            try {
                var cmap = type0Font.getCMap();
                if (cmap != null && cmap.getName() != null) encoding.setCmapName(cmap.getName());
            } catch (Exception ignored) {}
        }

        COSBase descendants = dict.getDictionaryObject(COSName.DESCENDANT_FONTS);
        if (descendants instanceof COSArray descArray && descArray.size() > 0) {
            COSBase firstDesc = descArray.getObject(0);
            encoding.setDescendantFont(summarizeCos(firstDesc));
            COSDictionary descDict = null;
            if (firstDesc instanceof COSDictionary d) descDict = d;
            else if (firstDesc instanceof COSObject cosObj && cosObj.getObject() instanceof COSDictionary d) descDict = d;
            if (descDict != null) {
                COSName descSubtype = descDict.getCOSName(COSName.SUBTYPE);
                if (descSubtype != null) encoding.setDescendantSubtype(descSubtype.getName());
            }
        }
        if (encoding.getDescendantSubtype() == null && font instanceof PDType0Font type0Font2) {
            try {
                var desc = type0Font2.getDescendantFont();
                if (desc != null) {
                    COSName descSubtype = desc.getCOSObject().getCOSName(COSName.SUBTYPE);
                    if (descSubtype != null) encoding.setDescendantSubtype(descSubtype.getName());
                    if (encoding.getDescendantFont() == null) encoding.setDescendantFont(summarizeCos(desc.getCOSObject()));
                }
            } catch (Exception e) {
                log.debug("Failed to get descendant font info for {}: {}", font.getName(), e.getMessage());
            }
        }
        return encoding;
    }

    Map<String, String> buildFontDictionary(PDFont font) {
        Map<String, String> dictionary = new LinkedHashMap<>();
        COSDictionary dict = font.getCOSObject();
        if (dict == null) return dictionary;
        List<COSName> names = new ArrayList<>(dict.keySet());
        names.sort(Comparator.comparing(COSName::getName));
        for (COSName name : names) {
            dictionary.put(name.getName(), summarizeCos(dict.getDictionaryObject(name)));
        }
        return dictionary;
    }

    List<FontDiagnostics.UsedCharacterIssue> buildUsedCharacterIssues(UsageStats stats) {
        List<FontDiagnostics.UsedCharacterIssue> issues = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : stats.usedChars.entrySet()) {
            String ch = entry.getKey();
            if (ch == null || ch.isEmpty()) continue;
            FontDiagnostics.UsedCharacterIssue issue = new FontDiagnostics.UsedCharacterIssue();
            issue.setCharacter(ch);
            issue.setUnicodeHex(unicodeHex(ch));
            issue.setCount(entry.getValue());
            issue.setIssue("Character is used in extracted text; inspect glyph coverage and encoding mapping.");
            issues.add(issue);
        }
        issues.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
        return issues;
    }

    List<FontDiagnostics.GlyphMapping> buildGlyphMappings(PDFont font, UsageStats stats) {
        boolean forceUnmapped = hasExplicitEmptyToUnicodeMap(font);
        Map<Integer, FontDiagnostics.GlyphMapping> mappings = new LinkedHashMap<>();

        int maxCode = (font instanceof PDType0Font) ? 65535 : 255;
        for (int code = 0; code <= maxCode; code++) {
            String unicode = safeToUnicode(font, code, forceUnmapped);
            int usedCount = stats.usedCodes.getOrDefault(code, 0);
            if ((unicode == null || unicode.isEmpty()) && usedCount == 0) continue;
            mappings.put(code, buildGlyphMapping(font, code, unicode, usedCount, stats));
        }

        for (Map.Entry<Integer, Integer> used : stats.usedCodes.entrySet()) {
            if (mappings.containsKey(used.getKey())) continue;
            int usedCode = used.getKey();
            mappings.put(usedCode, buildGlyphMapping(font, usedCode, null, used.getValue(), stats));
        }

        List<FontDiagnostics.GlyphMapping> list = new ArrayList<>(mappings.values());
        list.sort(Comparator.comparingInt(FontDiagnostics.GlyphMapping::getCode));
        return list;
    }

    private FontDiagnostics.GlyphMapping buildGlyphMapping(PDFont font, int code, String unicode,
                                                            int usedCount, UsageStats stats) {
        FontDiagnostics.GlyphMapping mapping = new FontDiagnostics.GlyphMapping();
        mapping.setCode(code);
        mapping.setUnicode(unicode);
        mapping.setUnicodeHex(unicodeHex(unicode));
        mapping.setUsedCount(usedCount);
        mapping.setMapped(unicode != null && !unicode.isEmpty());
        mapping.setWidth(safeWidth(font, code));
        String gName = safeGlyphName(font, code);
        mapping.setGlyphName(gName);
        boolean present = isGlyphPresent(font, code, gName);
        mapping.setGlyphPresent(present);
        applyDiagnosticStatuses(mapping, mapping.isMapped(), present, gName, font, code, unicode);
        return mapping;
    }

    void applyDiagnosticStatuses(FontDiagnostics.GlyphMapping mapping, boolean mapped, boolean glyphPresent,
                                  String glyphName, PDFont font, int code, String unicode) {
        boolean isNotdef = ".notdef".equals(glyphName);

        FontDiagnostics.RenderStatus renderStatus;
        if (!font.isEmbedded()) renderStatus = FontDiagnostics.RenderStatus.NOT_EMBEDDED;
        else if (!glyphPresent || isNotdef) renderStatus = FontDiagnostics.RenderStatus.GLYPH_MISSING;
        else renderStatus = FontDiagnostics.RenderStatus.OK;
        mapping.setRenderStatus(renderStatus);

        FontDiagnostics.ExtractionStatus extractionStatus;
        if (mapped) {
            boolean mismatch = false;
            if (font instanceof PDSimpleFont && unicode != null && !unicode.isEmpty() && glyphName != null) {
                try {
                    String aglUnicode = GlyphList.getAdobeGlyphList().toUnicode(glyphName);
                    if (aglUnicode != null && !aglUnicode.isEmpty() && !aglUnicode.equals(unicode)) mismatch = true;
                } catch (Exception ignored) {}
            }
            extractionStatus = mismatch ? FontDiagnostics.ExtractionStatus.ENCODING_MISMATCH : FontDiagnostics.ExtractionStatus.OK;
        } else {
            extractionStatus = FontDiagnostics.ExtractionStatus.NO_UNICODE_MAPPING;
        }
        mapping.setExtractionStatus(extractionStatus);

        FontDiagnostics.DiagnosticStatus combined;
        if (mapped && glyphPresent && !isNotdef) {
            combined = (extractionStatus == FontDiagnostics.ExtractionStatus.ENCODING_MISMATCH)
                ? FontDiagnostics.DiagnosticStatus.ENCODING_MISMATCH : FontDiagnostics.DiagnosticStatus.OK;
        } else if (!mapped && glyphPresent && !isNotdef) {
            combined = FontDiagnostics.DiagnosticStatus.NO_UNICODE_MAPPING;
        } else if (!glyphPresent || isNotdef) {
            combined = FontDiagnostics.DiagnosticStatus.GLYPH_NOT_IN_FONT;
        } else {
            combined = FontDiagnostics.DiagnosticStatus.UNKNOWN;
        }
        mapping.setDiagnosticStatus(combined);
    }

    String safeToUnicode(PDFont font, int code) { return safeToUnicode(font, code, false); }

    String safeToUnicode(PDFont font, int code, boolean forceUnmapped) {
        if (forceUnmapped) return null;
        try { return font.toUnicode(code); } catch (Exception ignored) { return null; }
    }

    boolean hasExplicitEmptyToUnicodeMap(PDFont font) {
        if (font == null || font.getCOSObject() == null) return false;
        COSBase toUnicode = font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
        if (!(toUnicode instanceof COSStream stream)) return false;
        try (InputStream is = stream.createInputStream()) {
            byte[] bytes = is.readAllBytes();
            if (bytes.length == 0) return true;
            String content = new String(bytes, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            return !(content.contains("beginbfchar") || content.contains("beginbfrange"));
        } catch (Exception ignored) { return false; }
    }

    Integer safeWidth(PDFont font, int code) {
        try { return Math.round(font.getWidth(code)); } catch (Exception ignored) { return null; }
    }

    String safeGlyphName(PDFont font, int code) {
        try {
            if (font instanceof PDSimpleFont simpleFont) {
                var encoding = simpleFont.getEncoding();
                if (encoding != null) {
                    String name = encoding.getName(code);
                    if (name != null) return name;
                }
            }
            if (font instanceof PDType0Font) return "CID+" + code;
        } catch (Exception ignored) {}
        return null;
    }

    boolean isGlyphPresent(PDFont font, int code, String glyphName) {
        try {
            if (".notdef".equals(glyphName)) return false;
            if (font instanceof PDType1Font type1Font && glyphName != null) return type1Font.hasGlyph(glyphName);
            if (font instanceof PDType0Font && !font.isEmbedded()) return false;
            return font.getWidth(code) > 0;
        } catch (Exception ignored) { return false; }
    }

    String summarizeCos(COSBase base) {
        if (base == null) return "";
        String text = base.toString();
        if (text == null) return "";
        if (text.length() > 220) text = text.substring(0, 220) + "…";
        return base.getClass().getSimpleName() + " " + text;
    }

    String safeName(COSName name) { return name == null ? "" : name.getName(); }

    String safeFontName(PDFont font) {
        try {
            String name = font.getName();
            return name == null ? "(unknown)" : name;
        } catch (Exception ignored) { return "(unknown)"; }
    }

    boolean isSubset(PDFont font) {
        String name = safeFontName(font);
        return name.length() > 7 && name.charAt(6) == '+';
    }

    String readEncoding(PDFont font) {
        try {
            COSBase encoding = font.getCOSObject().getDictionaryObject(COSName.ENCODING);
            return encoding == null ? "" : encoding.toString();
        } catch (Exception ignored) { return ""; }
    }

    boolean canEncode(PDFont font, String character) {
        if (character == null || character.isEmpty()) return true;
        try { font.encode(character); return true; } catch (Exception ignored) { return false; }
    }

    String unicodeHex(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("U+%04X", cp));
            offset += Character.charCount(cp);
        }
        return sb.toString();
    }
}
