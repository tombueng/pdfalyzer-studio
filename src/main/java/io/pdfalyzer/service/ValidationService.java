package io.pdfalyzer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.ValidationIssue;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ValidationService {

    public List<ValidationIssue> validate(byte[] pdfBytes) throws IOException {
        List<ValidationIssue> issues = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            validateMetadata(doc, issues);
            validateFonts(doc, issues);
            validatePages(doc, issues);
            validateAnnotations(doc, issues);
        }
        return issues;
    }

    private void validateMetadata(PDDocument doc, List<ValidationIssue> issues) {
        PDDocumentInformation info = doc.getDocumentInformation();

        PDMetadata metadata = doc.getDocumentCatalog().getMetadata();
        if (metadata == null) {
            issues.add(new ValidationIssue(
                    "WARNING", "META-001", "No XMP metadata stream found",
                    "PDF/A-1b, Section 6.7.11", "Document Catalog",
                    "metadata"));
        }

        if (info == null || info.getTitle() == null || info.getTitle().isEmpty()) {
            issues.add(new ValidationIssue(
                    "INFO", "META-002", "Document has no title",
                    "PDF 2.0, Section 14.3.3", "Document Info",
                    "metadata"));
        }

        if (info == null || info.getProducer() == null || info.getProducer().isEmpty()) {
            issues.add(new ValidationIssue(
                    "INFO", "META-003", "Document has no producer information",
                    "PDF 2.0, Section 14.3.3", "Document Info",
                    "metadata"));
        }
    }

    private void validateFonts(PDDocument doc, List<ValidationIssue> issues) {
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage page = doc.getPage(i);
            PDResources resources = page.getResources();
            if (resources == null)
                continue;

            for (COSName fontName : resources.getFontNames()) {
                try {
                    PDFont font = resources.getFont(fontName);

                    if (!font.isEmbedded()) {
                        issues.add(new ValidationIssue(
                                "ERROR", "FONT-001",
                                "Font '" + font.getName() + "' is not embedded on page " + (i + 1),
                                "PDF/A-1b, Section 6.3.5",
                                "Page " + (i + 1) + " / " + fontName.getName(),
                                "font"));
                    }

                    if (!font.getCOSObject().containsKey(COSName.TO_UNICODE)) {
                        issues.add(new ValidationIssue(
                                "WARNING", "FONT-002",
                                "Font '" + font.getName() + "' has no ToUnicode CMap on page " + (i + 1)
                                        + " - text extraction may fail",
                                "PDF/A-1b, Section 6.3.7",
                                "Page " + (i + 1) + " / " + fontName.getName(),
                                "font"));
                    }
                } catch (Exception e) {
                    issues.add(new ValidationIssue(
                            "ERROR", "FONT-ERR",
                            "Could not load font '" + fontName.getName() + "' on page " + (i + 1) + ": "
                                    + e.getMessage(),
                            "PDF 2.0, Section 9",
                            "Page " + (i + 1),
                            "font"));
                }
            }
        }
    }

    private void validatePages(PDDocument doc, List<ValidationIssue> issues) {
        if (doc.getNumberOfPages() == 0) {
            issues.add(new ValidationIssue(
                    "ERROR", "PAGE-001", "Document contains no pages",
                    "PDF 2.0, Section 7.7.3.2", "Document",
                    "structure"));
        }

        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage page = doc.getPage(i);
            if (page.getMediaBox() == null) {
                issues.add(new ValidationIssue(
                        "ERROR", "PAGE-002",
                        "Page " + (i + 1) + " has no MediaBox",
                        "PDF 2.0, Section 7.7.3.3",
                        "Page " + (i + 1),
                        "structure"));
            }
        }
    }

    private void validateAnnotations(PDDocument doc, List<ValidationIssue> issues) {
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            try {
                List<PDAnnotation> annotations = doc.getPage(i).getAnnotations();
                for (PDAnnotation annot : annotations) {
                    if (annot.getRectangle() == null) {
                        issues.add(new ValidationIssue(
                                "WARNING", "ANNOT-001",
                                "Annotation on page " + (i + 1) + " has no rectangle",
                                "PDF 2.0, Section 12.5.2",
                                "Page " + (i + 1),
                                "annotation"));
                    }

                    if (annot.getAppearance() == null) {
                        issues.add(new ValidationIssue(
                                "WARNING", "ANNOT-002",
                                "Annotation '" + annot.getSubtype() + "' on page " + (i + 1)
                                        + " has no appearance stream",
                                "PDF/A-1b, Section 6.5.3",
                                "Page " + (i + 1),
                                "annotation"));
                    }
                }
            } catch (Exception e) {
                log.debug("Error validating annotations on page {}", i, e);
            }
        }
    }
}
