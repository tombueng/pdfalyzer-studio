package io.pdfalyzer.service;

import io.pdfalyzer.model.FontInfo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
                throw new IllegalArgumentException("Invalid page index: " + pageIndex);
            }
            return analyzePageFontsInternal(doc.getPage(pageIndex), pageIndex);
        }
    }

    private List<FontInfo> analyzePageFontsInternal(PDPage page, int pageIndex) {
        List<FontInfo> fonts = new ArrayList<>();
        PDResources resources = page.getResources();
        if (resources == null) return fonts;

        for (COSName fontName : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(fontName);
                FontInfo info = new FontInfo();
                info.setFontName(font.getName());
                info.setFontType(font.getClass().getSimpleName().replace("PD", ""));
                info.setEmbedded(font.isEmbedded());
                info.setPageIndex(pageIndex);
                info.setObjectId(fontName.getName());

                // Subset detection
                String name = font.getName();
                if (name != null && name.length() > 7 && name.charAt(6) == '+') {
                    info.setSubset(true);
                }

                // Encoding - try to get encoding info from the COS dictionary
                try {
                    if (font.getCOSObject().containsKey(COSName.ENCODING)) {
                        info.setEncoding(font.getCOSObject().getDictionaryObject(COSName.ENCODING).toString());
                    }
                } catch (Exception ignored) {
                }

                // Check for issues
                PDFontDescriptor descriptor = font.getFontDescriptor();
                if (descriptor != null && !font.isEmbedded()) {
                    info.addIssue("Font is not embedded - may render differently across systems");
                }

                if (font instanceof PDType3Font) {
                    info.addIssue("Type3 font - may have rendering issues across platforms");
                }

                fonts.add(info);
            } catch (Exception e) {
                log.debug("Could not analyze font {} on page {}", fontName.getName(), pageIndex, e);
            }
        }

        return fonts;
    }
}
