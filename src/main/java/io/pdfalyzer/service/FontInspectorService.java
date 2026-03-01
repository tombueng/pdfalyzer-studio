package io.pdfalyzer.service;

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

    public List<Map<String, Object>> getFontUsageAreas(byte[] pdfBytes, int objNum, int genNum) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
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

    /**
     * Extract the embedded font file bytes for a font at the given indirect object reference.
     * Returns null if no embedded file is present.
     */
    public byte[] extractFontFile(byte[] pdfBytes, int objNum, int genNum) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            COSDocument cosDoc = doc.getDocument();
            COSObject cosObj = cosDoc.getObjectFromPool(new COSObjectKey(objNum, genNum));
            if (cosObj == null || !(cosObj.getObject() instanceof COSDictionary)) return null;
            COSDictionary fontDict = (COSDictionary) cosObj.getObject();
            COSBase fdBase = fontDict.getDictionaryObject(COSName.FONT_DESC);
            if (!(fdBase instanceof COSDictionary)) return null;
            COSDictionary fd = (COSDictionary) fdBase;
            for (String key : new String[]{"FontFile2", "FontFile3", "FontFile"}) {
                COSBase ff = fd.getDictionaryObject(COSName.getPDFName(key));
                if (ff instanceof COSStream) {
                    try (java.io.InputStream is = ((COSStream) ff).createInputStream()) {
                        return is.readAllBytes();
                    }
                }
            }
        }
        return null;
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
