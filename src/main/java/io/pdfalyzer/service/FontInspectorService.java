package io.pdfalyzer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.FontDiagnostics;
import io.pdfalyzer.model.FontInfo;
import io.pdfalyzer.service.FontCollectionHelper.FontRefAggregate;
import io.pdfalyzer.service.FontCollectionHelper.UsageStats;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class FontInspectorService {

    public static final class FontFileDownload {
        @Getter private final byte[] data;
        @Getter private final String filename;
        @Getter private final String contentType;

        public FontFileDownload(byte[] data, String filename, String contentType) {
            this.data = data;
            this.filename = filename;
            this.contentType = contentType;
        }
    }

    private final FontCollectionHelper collectionHelper;
    private final FontDiagnosticsBuilder diagnosticsBuilder;
    private final FontFileHelper fileHelper;

    public FontInspectorService(FontCollectionHelper collectionHelper,
                                FontDiagnosticsBuilder diagnosticsBuilder,
                                FontFileHelper fileHelper) {
        this.collectionHelper = collectionHelper;
        this.diagnosticsBuilder = diagnosticsBuilder;
        this.fileHelper = fileHelper;
    }

    public List<FontInfo> analyzeFonts(byte[] pdfBytes) throws IOException {
        List<FontInfo> results = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                results.addAll(collectionHelper.analyzePageFontsInternal(doc.getPage(i), i));
            }
        }
        return results;
    }

    public List<FontInfo> analyzePageFonts(byte[] pdfBytes, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages())
                throw new IllegalArgumentException("Invalid page index: " + pageIndex);
            return collectionHelper.analyzePageFontsInternal(doc.getPage(pageIndex), pageIndex);
        }
    }

    public FontDiagnostics analyzeFontIssues(byte[] pdfBytes) throws IOException {
        FontDiagnostics diagnostics = new FontDiagnostics();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Map<COSBase, COSObjectKey> objectKeyIndex = collectionHelper.buildObjectKeyIndex(doc);
            Map<String, FontRefAggregate> fonts = collectionHelper.collectAllFonts(doc, objectKeyIndex);
            Map<String, UsageStats> usage = collectionHelper.collectUsageByFont(doc, null, objectKeyIndex);

            List<FontDiagnostics.FontDiagnosticsEntry> entries = new ArrayList<>();
            int withIssues = 0, withMissingGlyphs = 0, withEncodingProblems = 0;

            for (Map.Entry<String, FontRefAggregate> ae : fonts.entrySet()) {
                UsageStats stats = usage.getOrDefault(ae.getKey(), new UsageStats());
                FontDiagnostics.FontDiagnosticsEntry entry = diagnosticsBuilder.buildDiagnosticsEntry(ae.getValue(), stats);
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
            Map<COSBase, COSObjectKey> objectKeyIndex = collectionHelper.buildObjectKeyIndex(doc);
            Map<String, FontRefAggregate> fonts = collectionHelper.collectAllFonts(doc, objectKeyIndex);
            String targetKey = objNum + ":" + genNum;
            FontRefAggregate aggregate = fonts.get(targetKey);
            if (aggregate == null) throw new IllegalArgumentException("Font object not found: " + objNum + " " + genNum);

            Map<String, UsageStats> usage = collectionHelper.collectUsageByFont(doc, targetKey, objectKeyIndex);
            UsageStats stats = usage.getOrDefault(targetKey, new UsageStats());

            FontDiagnostics.FontDiagnosticsDetail detail = new FontDiagnostics.FontDiagnosticsDetail();
            detail.setFont(diagnosticsBuilder.buildDiagnosticsEntry(aggregate, stats));
            detail.setEncoding(diagnosticsBuilder.buildEncodingDiagnostics(aggregate.font));
            detail.setFontDictionary(diagnosticsBuilder.buildFontDictionary(aggregate.font));
            detail.setUsedCharacterIssues(diagnosticsBuilder.buildUsedCharacterIssues(stats));
            List<FontDiagnostics.GlyphMapping> allMappings = diagnosticsBuilder.buildGlyphMappings(aggregate.font, stats);
            detail.setGlyphMappings(allMappings);
            detail.setMissingUsedGlyphMappings(
                allMappings.stream().filter(m -> m.getUsedCount() > 0 && !m.isMapped())
                    .collect(java.util.stream.Collectors.toList()));
            return detail;
        }
    }

    public List<Map<String, Object>> getFontUsageAreas(byte[] pdfBytes, int objNum, int genNum) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            Map<COSBase, COSObjectKey> objectKeyIndex = collectionHelper.buildObjectKeyIndex(doc);
            List<Map<String, Object>> result = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                Map<String, double[]> usage = new LinkedHashMap<>();
                final int targetPage = pageIndex;
                PDFTextStripper stripper = new PDFTextStripper() {
                    { setStartPage(targetPage + 1); setEndPage(targetPage + 1); }
                    @Override
                    protected void processTextPosition(TextPosition text) {
                        PDFont font = text.getFont();
                        if (font == null) { super.processTextPosition(text); return; }
                        COSObjectKey key = resolveKey(font, objectKeyIndex);
                        if (key == null || key.getNumber() != objNum || key.getGeneration() != genNum) {
                            super.processTextPosition(text);
                            return;
                        }
                        double x = text.getXDirAdj(), y = text.getYDirAdj();
                        double w = text.getWidthDirAdj(), h = text.getHeightDir();
                        double[] bb = usage.computeIfAbsent(font.getName(),
                                k -> new double[]{Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, 0});
                        bb[0] = Math.min(bb[0], x); bb[1] = Math.min(bb[1], y - h);
                        bb[2] = Math.max(bb[2], x + w); bb[3] = Math.max(bb[3], y); bb[4] += 1;
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
            Map<COSBase, COSObjectKey> objectKeyIndex = collectionHelper.buildObjectKeyIndex(doc);
            List<Map<String, Object>> result = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                final int targetPage = pageIndex;
                PDFTextStripper stripper = new PDFTextStripper() {
                    { setStartPage(targetPage + 1); setEndPage(targetPage + 1); }
                    @Override
                    protected void processTextPosition(TextPosition text) {
                        PDFont font = text.getFont();
                        if (font == null) { super.processTextPosition(text); return; }
                        COSObjectKey key = resolveKey(font, objectKeyIndex);
                        if (key == null || key.getNumber() != objNum || key.getGeneration() != genNum) {
                            super.processTextPosition(text); return;
                        }
                        int[] codes = text.getCharacterCodes();
                        if (codes == null || codes.length == 0) { super.processTextPosition(text); return; }
                        boolean match = false;
                        for (int code : codes) { if (code == glyphCode) { match = true; break; } }
                        if (!match) { super.processTextPosition(text); return; }
                        double x = text.getXDirAdj(), y = text.getYDirAdj();
                        double w = text.getWidthDirAdj(), h = text.getHeightDir();
                        if (w <= 0 || h <= 0) { super.processTextPosition(text); return; }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("fontName", diagnosticsBuilder.safeFontName(font));
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
            Map<COSBase, COSObjectKey> objectKeyIndex = collectionHelper.buildObjectKeyIndex(doc);
            Map<String, FontRefAggregate> fonts = collectionHelper.collectAllFonts(doc, objectKeyIndex);
            String targetKey = objNum + ":" + genNum;
            FontRefAggregate aggregate = fonts.get(targetKey);
            if (aggregate == null) throw new IllegalArgumentException("Font object not found: " + objNum + " " + genNum);

            Map<String, UsageStats> usage = collectionHelper.collectUsageByFont(doc, targetKey, objectKeyIndex);
            UsageStats stats = usage.getOrDefault(targetKey, new UsageStats());
            PDFont font = aggregate.font;

            String unicode = diagnosticsBuilder.safeToUnicode(font, glyphCode);
            Integer width = diagnosticsBuilder.safeWidth(font, glyphCode);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("objectRef", objNum + " " + genNum + " R");
            detail.put("fontName", diagnosticsBuilder.safeFontName(font));
            detail.put("fontType", font.getClass().getSimpleName().replace("PD", ""));
            detail.put("embedded", font.isEmbedded());
            detail.put("subset", diagnosticsBuilder.isSubset(font));
            detail.put("encoding", diagnosticsBuilder.readEncoding(font));

            FontDiagnostics.EncodingDiagnostics encodingDiagnostics = diagnosticsBuilder.buildEncodingDiagnostics(font);
            Map<String, Object> encoding = new LinkedHashMap<>();
            encoding.put("hasToUnicode", encodingDiagnostics.isHasToUnicode());
            encoding.put("encodingObject", encodingDiagnostics.getEncodingObject());
            encoding.put("toUnicodeObject", encodingDiagnostics.getToUnicodeObject());
            encoding.put("subtype", encodingDiagnostics.getSubtype());
            encoding.put("baseFont", encodingDiagnostics.getBaseFont());
            encoding.put("descendantFont", encodingDiagnostics.getDescendantFont());
            encoding.put("cmapName", encodingDiagnostics.getCmapName());
            encoding.put("descendantSubtype", encodingDiagnostics.getDescendantSubtype());
            detail.put("encodingDiagnostics", encoding);

            Map<String, Object> glyph = new LinkedHashMap<>();
            glyph.put("code", glyphCode);
            glyph.put("codeHex", String.format("0x%X", glyphCode));
            glyph.put("unicode", unicode == null ? "" : unicode);
            glyph.put("unicodeHex", diagnosticsBuilder.unicodeHex(unicode));
            glyph.put("mapped", unicode != null && !unicode.isEmpty());
            glyph.put("usedCount", stats.usedCodes.getOrDefault(glyphCode, 0));
            glyph.put("width", width);
            glyph.put("canEncodeMappedUnicode", unicode == null || unicode.isEmpty() || diagnosticsBuilder.canEncode(font, unicode));
            String gName = diagnosticsBuilder.safeGlyphName(font, glyphCode);
            boolean present = diagnosticsBuilder.isGlyphPresent(font, glyphCode, gName);
            glyph.put("glyphName", gName != null ? gName : "-");
            glyph.put("glyphPresent", present);
            FontDiagnostics.GlyphMapping tempMapping = new FontDiagnostics.GlyphMapping();
            tempMapping.setMapped(unicode != null && !unicode.isEmpty());
            diagnosticsBuilder.applyDiagnosticStatuses(tempMapping, tempMapping.isMapped(), present, gName, font, glyphCode, unicode);
            glyph.put("diagnosticStatus", tempMapping.getDiagnosticStatus().name());
            glyph.put("renderStatus", tempMapping.getRenderStatus().name());
            glyph.put("extractionStatus", tempMapping.getExtractionStatus().name());
            addVectorProps(glyph, font, glyphCode);
            detail.put("glyph", glyph);

            PDFontDescriptor descriptor = font.getFontDescriptor();
            if (descriptor != null) {
                Map<String, Object> descriptorMap = buildDescriptorMap(descriptor);
                detail.put("fontDescriptor", descriptorMap);
            }

            detail.put("pagesUsed", new ArrayList<>(new java.util.TreeSet<>(aggregate.pages)));
            detail.put("usageContexts", new ArrayList<>(new java.util.TreeSet<>(aggregate.contexts)));
            detail.put("usagePagesForCode", new ArrayList<>(stats.usedCodePages.getOrDefault(glyphCode, java.util.Collections.emptySet())));
            detail.put("unicodeSamplesForCode", stats.usedCodeUnicodeSamples.getOrDefault(glyphCode, List.of()));
            detail.put("kerningNote", "Pair-kerning extraction is font-format specific and not always exposed by PDFBox.");
            return detail;
        }
    }

    public byte[] extractFontFile(byte[] pdfBytes, int objNum, int genNum) throws IOException {
        FontFileDownload download = extractFontFileDownload(pdfBytes, objNum, genNum);
        return download != null ? download.getData() : null;
    }

    public FontFileDownload extractFontFileDownload(byte[] pdfBytes, int objNum, int genNum) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return fileHelper.extractFontFileDownload(doc, objNum, genNum);
        }
    }

    public Map<String, String> getCharacterMap(byte[] pdfBytes, int pageIndex,
                                                String fontObjectId) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return result;
            PDResources res = doc.getPage(pageIndex).getResources();
            if (res == null) return result;
            PDFont font;
            try {
                font = res.getFont(org.apache.pdfbox.cos.COSName.getPDFName(fontObjectId));
            } catch (Exception e) { return result; }
            if (font == null) return result;
            for (int code = 0; code <= 255; code++) {
                try {
                    String unicode = font.toUnicode(code);
                    if (unicode != null && !unicode.isEmpty())
                        result.put(String.valueOf(code), unicode);
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    private COSObjectKey resolveKey(PDFont font, Map<COSBase, COSObjectKey> objectKeyIndex) {
        COSObjectKey key = null;
        try {
            key = font.getCOSObject().getKey();
        } catch (Exception e) {
            log.trace("Could not read object key for font", e);
        }
        if (key == null && font.getCOSObject() != null) key = objectKeyIndex.get(font.getCOSObject());
        return key;
    }

    private void addVectorProps(Map<String, Object> glyph, PDFont font, int glyphCode) {
        try {
            org.apache.pdfbox.util.Vector displacement = font.getDisplacement(glyphCode);
            if (displacement != null) {
                Map<String, Object> disp = new LinkedHashMap<>();
                disp.put("x", displacement.getX()); disp.put("y", displacement.getY());
                glyph.put("displacement", disp);
            }
        } catch (Exception e) {
            log.trace("Could not read displacement for glyph {}", glyphCode, e);
        }
        try {
            org.apache.pdfbox.util.Vector positionVector = font.getPositionVector(glyphCode);
            if (positionVector != null) {
                Map<String, Object> pos = new LinkedHashMap<>();
                pos.put("x", positionVector.getX()); pos.put("y", positionVector.getY());
                glyph.put("positionVector", pos);
            }
        } catch (Exception e) {
            log.trace("Could not read positionVector for glyph {}", glyphCode, e);
        }
    }

    private Map<String, Object> buildDescriptorMap(PDFontDescriptor descriptor) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fontName", descriptor.getFontName());
        d.put("fontFamily", descriptor.getFontFamily());
        d.put("flags", descriptor.getFlags());
        d.put("italicAngle", descriptor.getItalicAngle());
        d.put("ascent", descriptor.getAscent());
        d.put("descent", descriptor.getDescent());
        d.put("capHeight", descriptor.getCapHeight());
        d.put("xHeight", descriptor.getXHeight());
        d.put("stemV", descriptor.getStemV());
        d.put("avgWidth", descriptor.getAverageWidth());
        d.put("maxWidth", descriptor.getMaxWidth());
        d.put("missingWidth", descriptor.getMissingWidth());
        if (descriptor.getFontBoundingBox() != null)
            d.put("fontBBox", descriptor.getFontBoundingBox().toString());
        return d;
    }
}
