package io.pdfalyzer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import io.pdfalyzer.model.FontInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects font references and usage statistics from a PDF document.
 * Used by {@link FontInspectorService}.
 */
@Component
@Slf4j
public class FontCollectionHelper {

    static class UsageStats {
        int glyphCount;
        int positionsWithoutUnicode;
        final Map<Integer, Integer> usedCodes = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> usedCodePages = new LinkedHashMap<>();
        final Map<Integer, List<String>> usedCodeUnicodeSamples = new LinkedHashMap<>();
        final Map<String, Integer> usedChars = new LinkedHashMap<>();
        final Set<Integer> pages = new LinkedHashSet<>();
    }

    static class FontRefAggregate {
        final PDFont font;
        final COSObjectKey key;
        final Set<Integer> pages = new LinkedHashSet<>();
        final Set<String> contexts = new LinkedHashSet<>();
        final Set<String> objectIds = new LinkedHashSet<>();

        FontRefAggregate(PDFont font, COSObjectKey key) {
            this.font = font;
            this.key = key;
        }
    }

    List<FontInfo> analyzePageFontsInternal(PDPage page, int pageIndex) {
        List<FontInfo> fonts = new ArrayList<>();
        PDResources resources = page.getResources();
        if (resources == null) return fonts;

        Map<String, FontInfo> dedup = new LinkedHashMap<>();
        Set<COSBase> visitedResources = Collections.newSetFromMap(new IdentityHashMap<>());
        analyzeResourcesRecursive(resources, pageIndex, "Page", dedup, visitedResources);

        fonts.addAll(dedup.values());
        return fonts;
    }

    private void analyzeResourcesRecursive(PDResources resources, int pageIndex, String context,
                                           Map<String, FontInfo> dedup, Set<COSBase> visitedResources) {
        if (resources == null) return;
        COSDictionary resCos = resources.getCOSObject();
        if (resCos != null && !visitedResources.add(resCos)) return;

        for (COSName fontName : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(fontName);
                String dedupKey = fontName.getName() + "@" + context + "@" + pageIndex;
                FontInfo info = dedup.computeIfAbsent(dedupKey, k -> buildFontInfo(font, pageIndex, fontName.getName(), context));

                String name = font.getName();
                if (name != null && name.length() > 7 && name.charAt(6) == '+')
                    info.setSubset(true);
                try {
                    if (font.getCOSObject().containsKey(COSName.ENCODING)) {
                        info.setEncoding(
                                font.getCOSObject().getDictionaryObject(COSName.ENCODING).toString());
                    }
                } catch (Exception ignored) {}
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
                    analyzeResourcesRecursive(((PDFormXObject) xObj).getResources(),
                            pageIndex, "XObject " + xObjName.getName(), dedup, visitedResources);
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect XObject resources on page {}", pageIndex, e);
        }
    }

    private FontInfo buildFontInfo(PDFont font, int pageIndex, String objectId, String context) {
        FontInfo info = new FontInfo();
        info.setFontName(font.getName());
        info.setFontType(font.getClass().getSimpleName().replace("PD", ""));
        info.setEmbedded(font.isEmbedded());
        info.setPageIndex(pageIndex);
        info.setObjectId(objectId);
        info.setUsageContext(context);
        return info;
    }

    Map<COSBase, COSObjectKey> buildObjectKeyIndex(PDDocument doc) {
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

    Map<String, FontRefAggregate> collectAllFonts(PDDocument doc, Map<COSBase, COSObjectKey> objectKeyIndex) {
        Map<String, FontRefAggregate> fonts = new LinkedHashMap<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            Set<COSBase> visitedResources = Collections.newSetFromMap(new IdentityHashMap<>());
            collectFontsRecursive(doc.getPage(i).getResources(), i, "Page", fonts, visitedResources, objectKeyIndex);
        }
        return fonts;
    }

    private void collectFontsRecursive(PDResources resources, int pageIndex, String context,
                                       Map<String, FontRefAggregate> out, Set<COSBase> visitedResources,
                                       Map<COSBase, COSObjectKey> objectKeyIndex) {
        if (resources == null) return;
        COSDictionary resCos = resources.getCOSObject();
        if (resCos != null && !visitedResources.add(resCos)) return;

        for (COSName fontName : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(fontName);
                COSObjectKey key = null;
                try { key = font.getCOSObject().getKey(); } catch (Exception ignored) {}
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
                    collectFontsRecursive(((PDFormXObject) xObj).getResources(),
                            pageIndex, "XObject " + xObjName.getName(), out, visitedResources, objectKeyIndex);
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect nested resources on page {}", pageIndex, e);
        }
    }

    Map<String, UsageStats> collectUsageByFont(PDDocument doc, String onlyFontKey,
                                               Map<COSBase, COSObjectKey> objectKeyIndex) throws IOException {
        Map<String, UsageStats> usageByFont = new LinkedHashMap<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                PDFont font = text.getFont();
                if (font == null) { super.processTextPosition(text); return; }

                COSObjectKey key = null;
                try { key = font.getCOSObject().getKey(); } catch (Exception ignored) {}
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
                        if (samples.size() < 8 && !samples.contains(unicode)) samples.add(unicode);
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

    String fontKey(COSObjectKey key, PDFont font, int pageIndex, String objectId, String context) {
        if (key != null) return key.getNumber() + ":" + key.getGeneration();
        return "direct:" + System.identityHashCode(font) + ":" + pageIndex + ":" + objectId + ":" + context;
    }

    List<String> detectMissingGlyphs(PDFont font) {
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
