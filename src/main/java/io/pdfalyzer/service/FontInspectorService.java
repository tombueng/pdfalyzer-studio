package io.pdfalyzer.service;

import io.pdfalyzer.model.FontDiagnostics;
import io.pdfalyzer.model.FontInfo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
public class FontInspectorService {

    private static final Logger log = LoggerFactory.getLogger(FontInspectorService.class);

    public static final class FontFileDownload {
        private final byte[] data;
        private final String filename;
        private final String contentType;

        public FontFileDownload(byte[] data, String filename, String contentType) {
            this.data = data;
            this.filename = filename;
            this.contentType = contentType;
        }

        public byte[] getData() {
            return data;
        }

        public String getFilename() {
            return filename;
        }

        public String getContentType() {
            return contentType;
        }
    }

    public List<FontInfo> analyzeFonts(byte[] pdfBytes) throws IOException {
        List<FontInfo> results = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                results.addAll(analyzePageFontsInternal(doc.getPage(i), i));
            }
        }
        return results;
    }

    public List<FontInfo> analyzePageFonts(byte[] pdfBytes, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages())
                throw new IllegalArgumentException("Invalid page index: " + pageIndex);
            return analyzePageFontsInternal(doc.getPage(pageIndex), pageIndex);
        }
    }

    public FontDiagnostics analyzeFontIssues(byte[] pdfBytes) throws IOException {
        FontDiagnostics diagnostics = new FontDiagnostics();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Map<COSBase, COSObjectKey> objectKeyIndex = buildObjectKeyIndex(doc);
            Map<String, FontRefAggregate> fonts = collectAllFonts(doc, objectKeyIndex);
            Map<String, UsageStats> usage = collectUsageByFont(doc, null, objectKeyIndex);

            List<FontDiagnostics.FontDiagnosticsEntry> entries = new ArrayList<>();
            int withIssues = 0;
            int withMissingGlyphs = 0;
            int withEncodingProblems = 0;

            for (Map.Entry<String, FontRefAggregate> aggregateEntry : fonts.entrySet()) {
                String key = aggregateEntry.getKey();
                FontRefAggregate aggregate = aggregateEntry.getValue();
                UsageStats stats = usage.getOrDefault(key, new UsageStats());
                FontDiagnostics.FontDiagnosticsEntry entry = buildDiagnosticsEntry(aggregate, stats);

                if (!entry.getIssues().isEmpty()) withIssues++;
                if (entry.getUnencodableUsedChars() > 0) withMissingGlyphs++;
                if (entry.getUnmappedUsedCodes() > 0) withEncodingProblems++;

                entries.add(entry);
            }

            entries.sort((a, b) -> {
                int issueCmp = Integer.compare(b.getIssues().size(), a.getIssues().size());
                if (issueCmp != 0) return issueCmp;
                int missingCmp = Integer.compare(b.getUnmappedUsedCodes(), a.getUnmappedUsedCodes());
                if (missingCmp != 0) return missingCmp;
                return String.valueOf(a.getFontName()).compareToIgnoreCase(String.valueOf(b.getFontName()));
            });

            diagnostics.setFonts(entries);
            diagnostics.setTotalFonts(entries.size());
            diagnostics.setFontsWithIssues(withIssues);
            diagnostics.setFontsWithMissingGlyphs(withMissingGlyphs);
            diagnostics.setFontsWithEncodingProblems(withEncodingProblems);
        }
        return diagnostics;
    }

    public FontDiagnostics.FontDiagnosticsDetail analyzeFontIssueDetail(byte[] pdfBytes, int objNum, int genNum) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Map<COSBase, COSObjectKey> objectKeyIndex = buildObjectKeyIndex(doc);
            Map<String, FontRefAggregate> fonts = collectAllFonts(doc, objectKeyIndex);
            String targetKey = objNum + ":" + genNum;
            FontRefAggregate aggregate = fonts.get(targetKey);
            if (aggregate == null) {
                throw new IllegalArgumentException("Font object not found: " + objNum + " " + genNum);
            }

            Map<String, UsageStats> usage = collectUsageByFont(doc, targetKey, objectKeyIndex);
            UsageStats stats = usage.getOrDefault(targetKey, new UsageStats());

            FontDiagnostics.FontDiagnosticsDetail detail = new FontDiagnostics.FontDiagnosticsDetail();
            detail.setFont(buildDiagnosticsEntry(aggregate, stats));
            detail.setEncoding(buildEncodingDiagnostics(aggregate.font));
            detail.setFontDictionary(buildFontDictionary(aggregate.font));
            detail.setUsedCharacterIssues(buildUsedCharacterIssues(stats));
            detail.setGlyphMappings(buildGlyphMappings(aggregate.font, stats));
            return detail;
        }
    }

    public List<Map<String, Object>> getFontUsageAreas(byte[] pdfBytes, int objNum, int genNum) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Map<COSBase, COSObjectKey> objectKeyIndex = buildObjectKeyIndex(doc);
            List<Map<String, Object>> result = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                Map<String, double[]> usage = new LinkedHashMap<>();
                final int targetPage = pageIndex;
                PDFTextStripper stripper = new PDFTextStripper() {
                    {
                        setStartPage(targetPage + 1);
                        setEndPage(targetPage + 1);
                    }

                    @Override
                    protected void processTextPosition(TextPosition text) {
                        PDFont font = text.getFont();
                        if (font == null) {
                            super.processTextPosition(text);
                            return;
                        }
                        COSObjectKey key = null;
                        try {
                            key = font.getCOSObject().getKey();
                        } catch (Exception ignored) {
                        }
                        if (key == null && font.getCOSObject() != null) {
                            key = objectKeyIndex.get(font.getCOSObject());
                        }
                        if (key == null || key.getNumber() != objNum || key.getGeneration() != genNum) {
                            super.processTextPosition(text);
                            return;
                        }

                        double x = text.getXDirAdj();
                        double y = text.getYDirAdj();
                        double w = text.getWidthDirAdj();
                        double h = text.getHeightDir();
                        String fontName = font.getName();
                        double[] bb = usage.computeIfAbsent(fontName,
                                k -> new double[]{Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, 0});
                        bb[0] = Math.min(bb[0], x);
                        bb[1] = Math.min(bb[1], y - h);
                        bb[2] = Math.max(bb[2], x + w);
                        bb[3] = Math.max(bb[3], y);
                        bb[4] += 1;
                        super.processTextPosition(text);
                    }
                };

                stripper.getText(doc);
                for (Map.Entry<String, double[]> entry : usage.entrySet()) {
                    double[] bb = entry.getValue();
                    if (bb[2] <= bb[0] || bb[3] <= bb[1]) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("fontName", entry.getKey());
                    row.put("pageIndex", pageIndex);
                    row.put("bbox", Arrays.asList(bb[0], bb[1], bb[2] - bb[0], bb[3] - bb[1]));
                    row.put("glyphCount", (int) bb[4]);
                    result.add(row);
                }
            }
            return result;
        }
    }

    public List<Map<String, Object>> getGlyphUsageAreas(byte[] pdfBytes, int objNum, int genNum, int glyphCode) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Map<COSBase, COSObjectKey> objectKeyIndex = buildObjectKeyIndex(doc);
            List<Map<String, Object>> result = new ArrayList<>();

            for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                final int targetPage = pageIndex;
                PDFTextStripper stripper = new PDFTextStripper() {
                    {
                        setStartPage(targetPage + 1);
                        setEndPage(targetPage + 1);
                    }

                    @Override
                    protected void processTextPosition(TextPosition text) {
                        PDFont font = text.getFont();
                        if (font == null) {
                            super.processTextPosition(text);
                            return;
                        }

                        COSObjectKey key = null;
                        try {
                            key = font.getCOSObject().getKey();
                        } catch (Exception ignored) {
                        }
                        if (key == null && font.getCOSObject() != null) {
                            key = objectKeyIndex.get(font.getCOSObject());
                        }
                        if (key == null || key.getNumber() != objNum || key.getGeneration() != genNum) {
                            super.processTextPosition(text);
                            return;
                        }

                        int[] codes = text.getCharacterCodes();
                        if (codes == null || codes.length == 0) {
                            super.processTextPosition(text);
                            return;
                        }
                        boolean match = false;
                        for (int code : codes) {
                            if (code == glyphCode) {
                                match = true;
                                break;
                            }
                        }
                        if (!match) {
                            super.processTextPosition(text);
                            return;
                        }

                        double x = text.getXDirAdj();
                        double y = text.getYDirAdj();
                        double w = text.getWidthDirAdj();
                        double h = text.getHeightDir();
                        if (w <= 0 || h <= 0) {
                            super.processTextPosition(text);
                            return;
                        }

                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("fontName", safeFontName(font));
                        row.put("pageIndex", targetPage);
                        row.put("bbox", Arrays.asList(x, y - h, w, h));
                        row.put("glyphCode", glyphCode);
                        row.put("unicode", text.getUnicode());
                        row.put("fontSize", text.getFontSizeInPt());
                        result.add(row);

                        super.processTextPosition(text);
                    }
                };
                stripper.getText(doc);
            }
            return result;
        }
    }

    public Map<String, Object> analyzeGlyphDetail(byte[] pdfBytes, int objNum, int genNum, int glyphCode) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Map<COSBase, COSObjectKey> objectKeyIndex = buildObjectKeyIndex(doc);
            Map<String, FontRefAggregate> fonts = collectAllFonts(doc, objectKeyIndex);
            String targetKey = objNum + ":" + genNum;
            FontRefAggregate aggregate = fonts.get(targetKey);
            if (aggregate == null) {
                throw new IllegalArgumentException("Font object not found: " + objNum + " " + genNum);
            }

            Map<String, UsageStats> usage = collectUsageByFont(doc, targetKey, objectKeyIndex);
            UsageStats stats = usage.getOrDefault(targetKey, new UsageStats());
            PDFont font = aggregate.font;

            String unicode = safeToUnicode(font, glyphCode);
            Integer width = safeWidth(font, glyphCode);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("objectRef", objNum + " " + genNum + " R");
            detail.put("fontName", safeFontName(font));
            detail.put("fontType", font.getClass().getSimpleName().replace("PD", ""));
            detail.put("embedded", font.isEmbedded());
            detail.put("subset", isSubset(font));
            detail.put("encoding", readEncoding(font));

            FontDiagnostics.EncodingDiagnostics encodingDiagnostics = buildEncodingDiagnostics(font);
            Map<String, Object> encoding = new LinkedHashMap<>();
            encoding.put("hasToUnicode", encodingDiagnostics.isHasToUnicode());
            encoding.put("encodingObject", encodingDiagnostics.getEncodingObject());
            encoding.put("toUnicodeObject", encodingDiagnostics.getToUnicodeObject());
            encoding.put("subtype", encodingDiagnostics.getSubtype());
            encoding.put("baseFont", encodingDiagnostics.getBaseFont());
            encoding.put("descendantFont", encodingDiagnostics.getDescendantFont());
            detail.put("encodingDiagnostics", encoding);

            Map<String, Object> glyph = new LinkedHashMap<>();
            glyph.put("code", glyphCode);
            glyph.put("codeHex", String.format("0x%X", glyphCode));
            glyph.put("unicode", unicode == null ? "" : unicode);
            glyph.put("unicodeHex", unicodeHex(unicode));
            glyph.put("mapped", unicode != null && !unicode.isEmpty());
            glyph.put("usedCount", stats.usedCodes.getOrDefault(glyphCode, 0));
            glyph.put("width", width);
            glyph.put("canEncodeMappedUnicode", unicode == null || unicode.isEmpty() || canEncode(font, unicode));

            try {
                org.apache.pdfbox.util.Vector displacement = font.getDisplacement(glyphCode);
                if (displacement != null) {
                    Map<String, Object> disp = new LinkedHashMap<>();
                    disp.put("x", displacement.getX());
                    disp.put("y", displacement.getY());
                    glyph.put("displacement", disp);
                }
            } catch (Exception ignored) {
            }
            try {
                org.apache.pdfbox.util.Vector positionVector = font.getPositionVector(glyphCode);
                if (positionVector != null) {
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", positionVector.getX());
                    pos.put("y", positionVector.getY());
                    glyph.put("positionVector", pos);
                }
            } catch (Exception ignored) {
            }
            detail.put("glyph", glyph);

            PDFontDescriptor descriptor = font.getFontDescriptor();
            if (descriptor != null) {
                Map<String, Object> descriptorMap = new LinkedHashMap<>();
                descriptorMap.put("fontName", descriptor.getFontName());
                descriptorMap.put("fontFamily", descriptor.getFontFamily());
                descriptorMap.put("flags", descriptor.getFlags());
                descriptorMap.put("italicAngle", descriptor.getItalicAngle());
                descriptorMap.put("ascent", descriptor.getAscent());
                descriptorMap.put("descent", descriptor.getDescent());
                descriptorMap.put("capHeight", descriptor.getCapHeight());
                descriptorMap.put("xHeight", descriptor.getXHeight());
                descriptorMap.put("stemV", descriptor.getStemV());
                descriptorMap.put("avgWidth", descriptor.getAverageWidth());
                descriptorMap.put("maxWidth", descriptor.getMaxWidth());
                descriptorMap.put("missingWidth", descriptor.getMissingWidth());
                if (descriptor.getFontBoundingBox() != null) {
                    descriptorMap.put("fontBBox", descriptor.getFontBoundingBox().toString());
                }
                detail.put("fontDescriptor", descriptorMap);
            }

            List<Integer> pages = new ArrayList<>(new TreeSet<>(aggregate.pages));
            detail.put("pagesUsed", pages);
            detail.put("usageContexts", new ArrayList<>(new TreeSet<>(aggregate.contexts)));
            detail.put("usagePagesForCode", new ArrayList<>(stats.usedCodePages.getOrDefault(glyphCode, Collections.emptySet())));
            detail.put("unicodeSamplesForCode", stats.usedCodeUnicodeSamples.getOrDefault(glyphCode, List.of()));
            detail.put("kerningNote", "Pair-kerning extraction is font-format specific and not always exposed by PDFBox. Displacement/position vectors and descriptor metrics are provided when available.");

            return detail;
        }
    }

    /**
     * Extract the embedded font file bytes for a font at the given indirect object reference.
     * Returns null if no embedded file is present.
     */
    public byte[] extractFontFile(byte[] pdfBytes, int objNum, int genNum) throws IOException {
        FontFileDownload download = extractFontFileDownload(pdfBytes, objNum, genNum);
        return download != null ? download.getData() : null;
    }

    public FontFileDownload extractFontFileDownload(byte[] pdfBytes, int objNum, int genNum) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            COSDocument cosDoc = doc.getDocument();
            COSObject cosObj = cosDoc.getObjectFromPool(new COSObjectKey(objNum, genNum));
            if (cosObj == null || !(cosObj.getObject() instanceof COSDictionary)) return null;
            COSDictionary fontDict = (COSDictionary) cosObj.getObject();
            COSDictionary descriptor = resolveFontDescriptor(fontDict);
            if (descriptor == null) return null;

            for (String key : new String[]{"FontFile3", "FontFile2", "FontFile"}) {
                COSBase fontFile = dereference(descriptor.getDictionaryObject(COSName.getPDFName(key)));
                if (fontFile instanceof COSStream) {
                    try (java.io.InputStream is = ((COSStream) fontFile).createInputStream()) {
                        byte[] data = is.readAllBytes();
                        String extension = determineFontExtension(key, (COSStream) fontFile);
                        String filename = buildDownloadFilename(fontDict, descriptor, extension, objNum, genNum);
                        String contentType = determineFontContentType(extension);
                        return new FontFileDownload(data, filename, contentType);
                    }
                }
            }
        }
        return null;
    }

    private String buildDownloadFilename(COSDictionary fontDict,
                                         COSDictionary descriptor,
                                         String extension,
                                         int objNum,
                                         int genNum) {
        String family = sanitizeFilenamePart(descriptor.getString(COSName.FONT_FAMILY));
        String fontName = sanitizeFilenamePart(cleanSubsetPrefix(readFontName(fontDict, descriptor)));

        StringBuilder base = new StringBuilder();
        if (!family.isBlank()) {
            base.append(family);
        }
        if (!fontName.isBlank() && !fontName.equalsIgnoreCase(family)) {
            if (base.length() > 0) {
                base.append('-');
            }
            base.append(fontName);
        }
        if (base.length() == 0) {
            base.append("font");
        }

        return base + "-" + objNum + "-" + genNum + "." + extension;
    }

    private String readFontName(COSDictionary fontDict, COSDictionary descriptor) {
        String name = descriptor.getNameAsString(COSName.FONT_NAME);
        if (name != null && !name.isBlank()) {
            return name;
        }
        name = fontDict.getNameAsString(COSName.BASE_FONT);
        if (name != null && !name.isBlank()) {
            return name;
        }
        COSBase descendants = dereference(fontDict.getDictionaryObject(COSName.DESCENDANT_FONTS));
        if (descendants instanceof COSArray descendantArray && descendantArray.size() > 0) {
            COSBase descendant = dereference(descendantArray.get(0));
            if (descendant instanceof COSDictionary descendantDict) {
                String descendantName = descendantDict.getNameAsString(COSName.BASE_FONT);
                if (descendantName != null && !descendantName.isBlank()) {
                    return descendantName;
                }
            }
        }
        return "font";
    }

    private String determineFontExtension(String fontFileKey, COSStream fontFileStream) {
        if ("FontFile2".equals(fontFileKey)) {
            return "ttf";
        }
        if ("FontFile".equals(fontFileKey)) {
            return "pfb";
        }

        String subtype = fontFileStream.getNameAsString(COSName.SUBTYPE);
        if (subtype == null || subtype.isBlank()) {
            return "bin";
        }
        String normalized = subtype.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "opentype" -> "otf";
            case "type1c", "cidfonttype0c" -> "cff";
            case "cidfonttype2", "truetype" -> "ttf";
            default -> "bin";
        };
    }

    private String determineFontContentType(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "pfb" -> "application/x-font-type1";
            case "cff" -> "application/font-sfnt";
            default -> "application/octet-stream";
        };
    }

    private String cleanSubsetPrefix(String fontName) {
        if (fontName == null) {
            return "";
        }
        int plus = fontName.indexOf('+');
        if (plus == 6) {
            return fontName.substring(plus + 1);
        }
        return fontName;
    }

    private String sanitizeFilenamePart(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        normalized = normalized.replaceAll("-+", "-");
        return normalized.replaceAll("(^[-.]+|[-.]+$)", "");
    }

    private COSDictionary resolveFontDescriptor(COSDictionary fontDict) {
        COSBase directDescriptor = dereference(fontDict.getDictionaryObject(COSName.FONT_DESC));
        if (directDescriptor instanceof COSDictionary) {
            return (COSDictionary) directDescriptor;
        }

        COSBase descendants = dereference(fontDict.getDictionaryObject(COSName.DESCENDANT_FONTS));
        if (!(descendants instanceof COSArray)) {
            return null;
        }

        COSArray descendantArray = (COSArray) descendants;
        for (int i = 0; i < descendantArray.size(); i++) {
            COSBase descendant = dereference(descendantArray.get(i));
            if (!(descendant instanceof COSDictionary)) {
                continue;
            }
            COSBase descendantDescriptor =
                    dereference(((COSDictionary) descendant).getDictionaryObject(COSName.FONT_DESC));
            if (descendantDescriptor instanceof COSDictionary) {
                return (COSDictionary) descendantDescriptor;
            }
        }
        return null;
    }

    private COSBase dereference(COSBase value) {
        if (value instanceof COSObject) {
            return ((COSObject) value).getObject();
        }
        return value;
    }

    /**
     * Return the character-to-unicode mapping for a named font on a given page.
     * Keys are decimal codepoints (as strings), values are Unicode strings.
     */
    public Map<String, String> getCharacterMap(byte[] pdfBytes, int pageIndex,
                                                String fontObjectId) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return result;
            PDResources res = doc.getPage(pageIndex).getResources();
            if (res == null) return result;
            PDFont font;
            try {
                font = res.getFont(COSName.getPDFName(fontObjectId));
            } catch (Exception e) {
                return result;
            }
            if (font == null) return result;
            for (int code = 0; code <= 255; code++) {
                try {
                    String unicode = font.toUnicode(code);
                    if (unicode != null && !unicode.isEmpty()) {
                        result.put(String.valueOf(code), unicode);
                    }
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    private List<FontInfo> analyzePageFontsInternal(PDPage page, int pageIndex) {
        List<FontInfo> fonts = new ArrayList<>();
        PDResources resources = page.getResources();
        if (resources == null) return fonts;

        Map<String, FontInfo> dedup = new LinkedHashMap<>();
        Set<COSBase> visitedResources = Collections.newSetFromMap(new IdentityHashMap<>());
        analyzeResourcesRecursive(resources, pageIndex, "Page", dedup, visitedResources);

        fonts.addAll(dedup.values());
        return fonts;
    }

    private void analyzeResourcesRecursive(PDResources resources,
                                           int pageIndex,
                                           String context,
                                           Map<String, FontInfo> dedup,
                                           Set<COSBase> visitedResources) {
        if (resources == null) return;
        COSDictionary resCos = resources.getCOSObject();
        if (resCos != null && !visitedResources.add(resCos)) {
            return;
        }

        for (COSName fontName : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(fontName);
                String dedupKey = fontName.getName() + "@" + context + "@" + pageIndex;
                FontInfo info = dedup.get(dedupKey);
                if (info == null) {
                    info = new FontInfo();
                    info.setFontName(font.getName());
                    info.setFontType(font.getClass().getSimpleName().replace("PD", ""));
                    info.setEmbedded(font.isEmbedded());
                    info.setPageIndex(pageIndex);
                    info.setObjectId(fontName.getName());
                    info.setUsageContext(context);
                    dedup.put(dedupKey, info);
                }

                String name = font.getName();
                if (name != null && name.length() > 7 && name.charAt(6) == '+')
                    info.setSubset(true);
                try {
                    if (font.getCOSObject().containsKey(COSName.ENCODING)) {
                        info.setEncoding(
                                font.getCOSObject().getDictionaryObject(COSName.ENCODING).toString());
                    }
                } catch (Exception ignored) {}
                // Store object key for extraction
                COSObjectKey key = null;
                try { key = font.getCOSObject().getKey(); } catch (Exception ignored) {}
                if (key != null) {
                    info.setObjectNumber((int) key.getNumber());
                    info.setGenerationNumber((int) key.getGeneration());
                }

                PDFontDescriptor descriptor = font.getFontDescriptor();
                if (descriptor != null && !font.isEmbedded()) {
                    info.addIssue("Font is not embedded - may render differently across systems");
                    info.setFixSuggestion("Embed this font in source PDF or convert to embedded subset.");
                }
                if (font instanceof PDType3Font) {
                    info.addIssue("Type3 font - may have rendering issues across platforms");
                    info.setFixSuggestion("Replace Type3 font with embedded TrueType/OpenType when possible.");
                }

                List<String> missing = detectMissingGlyphs(font);
                if (!missing.isEmpty()) {
                    info.addIssue("Potential missing glyphs: " + String.join("", missing));
                    if (info.getFixSuggestion() == null) {
                        info.setFixSuggestion("Use a font with full glyph coverage for required characters.");
                    }
                }
            } catch (Exception e) {
                log.debug("Could not analyze font {} on page {}", fontName.getName(), pageIndex, e);
            }
        }

        try {
            for (COSName xObjName : resources.getXObjectNames()) {
                PDXObject xObj = resources.getXObject(xObjName);
                if (xObj instanceof PDFormXObject) {
                    PDFormXObject form = (PDFormXObject) xObj;
                    analyzeResourcesRecursive(form.getResources(), pageIndex,
                            "XObject " + xObjName.getName(), dedup, visitedResources);
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect XObject resources on page {}", pageIndex, e);
        }
    }

    private Map<COSBase, COSObjectKey> buildObjectKeyIndex(PDDocument doc) {
        Map<COSBase, COSObjectKey> index = new IdentityHashMap<>();
        try {
            COSDocument cosDocument = doc.getDocument();
            Map<COSObjectKey, Long> xref = cosDocument.getXrefTable();
            for (COSObjectKey key : xref.keySet()) {
                COSObject object = cosDocument.getObjectFromPool(key);
                if (object == null) continue;
                COSBase base = object.getObject();
                if (base != null) index.put(base, key);
            }
        } catch (Exception e) {
            log.debug("Could not build COS object-key index", e);
        }
        return index;
    }

    private Map<String, FontRefAggregate> collectAllFonts(PDDocument doc, Map<COSBase, COSObjectKey> objectKeyIndex) {
        Map<String, FontRefAggregate> fonts = new LinkedHashMap<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            Set<COSBase> visitedResources = Collections.newSetFromMap(new IdentityHashMap<>());
            collectFontsRecursive(doc.getPage(i).getResources(), i, "Page", fonts, visitedResources, objectKeyIndex);
        }
        return fonts;
    }

    private void collectFontsRecursive(PDResources resources,
                                       int pageIndex,
                                       String context,
                                       Map<String, FontRefAggregate> out,
                                       Set<COSBase> visitedResources,
                                       Map<COSBase, COSObjectKey> objectKeyIndex) {
        if (resources == null) return;
        COSDictionary resCos = resources.getCOSObject();
        if (resCos != null && !visitedResources.add(resCos)) return;

        for (COSName fontName : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(fontName);
                COSObjectKey key = null;
                try {
                    key = font.getCOSObject().getKey();
                } catch (Exception ignored) {
                }
                if (key == null && font.getCOSObject() != null) {
                    key = objectKeyIndex.get(font.getCOSObject());
                }
                final COSObjectKey capturedKey = key;
                String refKey = fontKey(key, font, pageIndex, fontName.getName(), context);
                FontRefAggregate aggregate = out.computeIfAbsent(refKey, k -> new FontRefAggregate(font, capturedKey));
                aggregate.pages.add(pageIndex);
                aggregate.contexts.add(context);
                aggregate.objectIds.add(fontName.getName());
            } catch (Exception e) {
                log.debug("Could not collect font {} on page {}", fontName.getName(), pageIndex, e);
            }
        }

        try {
            for (COSName xObjName : resources.getXObjectNames()) {
                PDXObject xObj = resources.getXObject(xObjName);
                if (xObj instanceof PDFormXObject) {
                    PDFormXObject form = (PDFormXObject) xObj;
                    collectFontsRecursive(form.getResources(), pageIndex,
                            "XObject " + xObjName.getName(), out, visitedResources, objectKeyIndex);
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect nested resources on page {}", pageIndex, e);
        }
    }

    private Map<String, UsageStats> collectUsageByFont(PDDocument doc,
                                                       String onlyFontKey,
                                                       Map<COSBase, COSObjectKey> objectKeyIndex) throws IOException {
        Map<String, UsageStats> usageByFont = new LinkedHashMap<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                PDFont font = text.getFont();
                if (font == null) {
                    super.processTextPosition(text);
                    return;
                }

                COSObjectKey key = null;
                try {
                    key = font.getCOSObject().getKey();
                } catch (Exception ignored) {
                }
                if (key == null && font.getCOSObject() != null) {
                    key = objectKeyIndex.get(font.getCOSObject());
                }
                String refKey = fontKey(key, font, getCurrentPageNo() - 1, "", "");
                if (onlyFontKey != null && !onlyFontKey.equals(refKey)) {
                    super.processTextPosition(text);
                    return;
                }

                UsageStats usage = usageByFont.computeIfAbsent(refKey, k -> new UsageStats());
                usage.pages.add(getCurrentPageNo() - 1);
                usage.glyphCount++;

                int[] codes = text.getCharacterCodes();
                if (codes != null) {
                    for (int code : codes) {
                        usage.usedCodes.merge(code, 1, Integer::sum);
                        usage.usedCodePages.computeIfAbsent(code, k -> new LinkedHashSet<>()).add(getCurrentPageNo() - 1);
                    }
                }

                String unicode = text.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    usage.positionsWithoutUnicode++;
                } else {
                    if (codes != null && codes.length == 1) {
                        int code = codes[0];
                        List<String> samples = usage.usedCodeUnicodeSamples.computeIfAbsent(code, k -> new ArrayList<>());
                        if (samples.size() < 8 && !samples.contains(unicode)) {
                            samples.add(unicode);
                        }
                    }
                    for (int offset = 0; offset < unicode.length(); ) {
                        int cp = unicode.codePointAt(offset);
                        String ch = new String(Character.toChars(cp));
                        usage.usedChars.merge(ch, 1, Integer::sum);
                        offset += Character.charCount(cp);
                    }
                }

                super.processTextPosition(text);
            }
        };
        stripper.getText(doc);
        return usageByFont;
    }

    private FontDiagnostics.FontDiagnosticsEntry buildDiagnosticsEntry(FontRefAggregate aggregate, UsageStats stats) {
        FontDiagnostics.FontDiagnosticsEntry entry = new FontDiagnostics.FontDiagnosticsEntry();
        PDFont font = aggregate.font;

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

        int mappedUsedCodes = 0;
        int unmappedUsedCodes = 0;
        for (int code : stats.usedCodes.keySet()) {
            String unicode = safeToUnicode(font, code);
            if (unicode == null || unicode.isEmpty()) unmappedUsedCodes++;
            else mappedUsedCodes++;
        }
        entry.setMappedUsedCodes(mappedUsedCodes);
        entry.setUnmappedUsedCodes(unmappedUsedCodes);

        int unencodableChars = 0;
        List<String> unencodableSamples = new ArrayList<>();
        for (String character : stats.usedChars.keySet()) {
            if (!canEncode(font, character)) {
                unencodableChars += 1;
                if (unencodableSamples.size() < 6) {
                    unencodableSamples.add(character + " (" + unicodeHex(character) + ")");
                }
            }
        }
        entry.setUnencodableUsedChars(unencodableChars);

        List<Integer> pages = new ArrayList<>(new TreeSet<>(aggregate.pages));
        List<String> contexts = new ArrayList<>(new TreeSet<>(aggregate.contexts));
        entry.setPagesUsed(pages);
        entry.setUsageContexts(contexts);

        if (!font.isEmbedded()) {
            entry.getIssues().add("Font is not embedded");
            entry.setFixSuggestion("Embed this font or replace with an embedded subset to avoid platform-dependent rendering.");
        }
        if (font instanceof PDType3Font) {
            entry.getIssues().add("Type3 font can cause inconsistent rendering");
            if (entry.getFixSuggestion() == null) {
                entry.setFixSuggestion("Prefer embedded TrueType/OpenType fonts for better compatibility.");
            }
        }
        if (entry.getUnmappedUsedCodes() > 0) {
            entry.getIssues().add("Used character codes are missing Unicode mappings: " + entry.getUnmappedUsedCodes());
            if (entry.getFixSuggestion() == null) {
                entry.setFixSuggestion("Add or repair ToUnicode CMap for this font.");
            }
        }
        if (entry.getUnencodableUsedChars() > 0) {
            entry.getIssues().add("Used text contains characters not encodable by this font: " + entry.getUnencodableUsedChars());
            if (entry.getFixSuggestion() == null) {
                entry.setFixSuggestion("Use a font that covers all required glyphs or split text across fallback fonts.");
            }
        }
        if (stats.positionsWithoutUnicode > 0) {
            entry.getIssues().add("Text positions without Unicode output: " + stats.positionsWithoutUnicode);
            if (entry.getFixSuggestion() == null) {
                entry.setFixSuggestion("Verify Encoding and ToUnicode entries for this font.");
            }
        }

        return entry;
    }

    private FontDiagnostics.EncodingDiagnostics buildEncodingDiagnostics(PDFont font) {
        FontDiagnostics.EncodingDiagnostics encoding = new FontDiagnostics.EncodingDiagnostics();
        COSDictionary dict = font.getCOSObject();
        encoding.setHasToUnicode(dict.containsKey(COSName.TO_UNICODE));
        encoding.setEncodingObject(summarizeCos(dict.getDictionaryObject(COSName.ENCODING)));
        encoding.setToUnicodeObject(summarizeCos(dict.getDictionaryObject(COSName.TO_UNICODE)));
        encoding.setSubtype(safeName(dict.getCOSName(COSName.SUBTYPE)));
        encoding.setBaseFont(safeName(dict.getCOSName(COSName.BASE_FONT)));

        COSBase descendants = dict.getDictionaryObject(COSName.DESCENDANT_FONTS);
        if (descendants instanceof COSArray && ((COSArray) descendants).size() > 0) {
            encoding.setDescendantFont(summarizeCos(((COSArray) descendants).getObject(0)));
        }
        return encoding;
    }

    private Map<String, String> buildFontDictionary(PDFont font) {
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

    private List<FontDiagnostics.UsedCharacterIssue> buildUsedCharacterIssues(UsageStats stats) {
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

    private List<FontDiagnostics.GlyphMapping> buildGlyphMappings(PDFont font, UsageStats stats) {
        Map<Integer, FontDiagnostics.GlyphMapping> mappings = new LinkedHashMap<>();

        int maxCode = (font instanceof PDType0Font) ? 65535 : 255;
        for (int code = 0; code <= maxCode; code++) {
            String unicode = safeToUnicode(font, code);
            int usedCount = stats.usedCodes.getOrDefault(code, 0);
            if ((unicode == null || unicode.isEmpty()) && usedCount == 0) continue;

            FontDiagnostics.GlyphMapping mapping = new FontDiagnostics.GlyphMapping();
            mapping.setCode(code);
            mapping.setUnicode(unicode);
            mapping.setUnicodeHex(unicodeHex(unicode));
            mapping.setUsedCount(usedCount);
            mapping.setMapped(unicode != null && !unicode.isEmpty());
            mapping.setWidth(safeWidth(font, code));
            mappings.put(code, mapping);
        }

        for (Map.Entry<Integer, Integer> used : stats.usedCodes.entrySet()) {
            if (mappings.containsKey(used.getKey())) continue;
            FontDiagnostics.GlyphMapping mapping = new FontDiagnostics.GlyphMapping();
            mapping.setCode(used.getKey());
            mapping.setUnicode(null);
            mapping.setUnicodeHex("");
            mapping.setUsedCount(used.getValue());
            mapping.setMapped(false);
            mapping.setWidth(safeWidth(font, used.getKey()));
            mappings.put(used.getKey(), mapping);
        }

        List<FontDiagnostics.GlyphMapping> list = new ArrayList<>(mappings.values());
        list.sort(Comparator.comparingInt(FontDiagnostics.GlyphMapping::getCode));
        return list;
    }

    private String safeToUnicode(PDFont font, int code) {
        try {
            return font.toUnicode(code);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer safeWidth(PDFont font, int code) {
        try {
            return Math.round(font.getWidth(code));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String summarizeCos(COSBase base) {
        if (base == null) return "";
        String text = base.toString();
        if (text == null) return "";
        if (text.length() > 220) text = text.substring(0, 220) + "…";
        return base.getClass().getSimpleName() + " " + text;
    }

    private String safeName(COSName name) {
        return name == null ? "" : name.getName();
    }

    private String safeFontName(PDFont font) {
        try {
            String name = font.getName();
            return name == null ? "(unknown)" : name;
        } catch (Exception ignored) {
            return "(unknown)";
        }
    }

    private boolean isSubset(PDFont font) {
        String name = safeFontName(font);
        return name.length() > 7 && name.charAt(6) == '+';
    }

    private String readEncoding(PDFont font) {
        try {
            COSBase encoding = font.getCOSObject().getDictionaryObject(COSName.ENCODING);
            return encoding == null ? "" : encoding.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean canEncode(PDFont font, String character) {
        if (character == null || character.isEmpty()) return true;
        try {
            font.encode(character);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String unicodeHex(String text) {
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

    private String fontKey(COSObjectKey key, PDFont font, int pageIndex, String objectId, String context) {
        if (key != null) return key.getNumber() + ":" + key.getGeneration();
        return "direct:" + System.identityHashCode(font) + ":" + pageIndex + ":" + objectId + ":" + context;
    }

    private static class UsageStats {
        private int glyphCount;
        private int positionsWithoutUnicode;
        private final Map<Integer, Integer> usedCodes = new LinkedHashMap<>();
        private final Map<Integer, Set<Integer>> usedCodePages = new LinkedHashMap<>();
        private final Map<Integer, List<String>> usedCodeUnicodeSamples = new LinkedHashMap<>();
        private final Map<String, Integer> usedChars = new LinkedHashMap<>();
        private final Set<Integer> pages = new LinkedHashSet<>();
    }

    private static class FontRefAggregate {
        private final PDFont font;
        private final COSObjectKey key;
        private final Set<Integer> pages = new LinkedHashSet<>();
        private final Set<String> contexts = new LinkedHashSet<>();
        private final Set<String> objectIds = new LinkedHashSet<>();

        private FontRefAggregate(PDFont font, COSObjectKey key) {
            this.font = font;
            this.key = key;
        }
    }

    private List<String> detectMissingGlyphs(PDFont font) {
        List<String> missing = new ArrayList<>();
        String sample = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789äöüÄÖÜß€";
        for (int i = 0; i < sample.length(); i++) {
            String character = String.valueOf(sample.charAt(i));
            try {
                font.encode(character);
            } catch (Exception ex) {
                missing.add(character);
                if (missing.size() >= 8) break;
            }
        }
        return missing;
    }
}
