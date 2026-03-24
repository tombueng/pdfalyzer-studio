package io.pdfalyzer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
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
            validateFormFields(doc, issues);
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

    private void validateFormFields(PDDocument doc, List<ValidationIssue> issues) {
        int widgetCount = 0;
        Set<COSDictionary> pageWidgetDicts = new HashSet<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            try {
                for (PDAnnotation annot : doc.getPage(i).getAnnotations()) {
                    if ("Widget".equals(annot.getSubtype())) {
                        widgetCount++;
                        pageWidgetDicts.add(annot.getCOSObject());
                    }
                }
            } catch (Exception e) {
                log.debug("Error scanning page {} for widget annotations", i, e);
            }
        }

        // FORM-003: NeedAppearances is true
        // Read directly from COS dictionary BEFORE getAcroForm() — PDFBox 3.x
        // generates appearances in getAcroForm() which resets NeedAppearances.
        COSDictionary catalogDict = doc.getDocumentCatalog().getCOSObject();
        COSBase acroFormBase = catalogDict.getDictionaryObject(COSName.getPDFName("AcroForm"));
        boolean needAppearancesTrue = false;
        if (acroFormBase instanceof COSDictionary acroFormDict) {
            needAppearancesTrue = acroFormDict.getBoolean(COSName.getPDFName("NeedAppearances"), false);
        }

        PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();

        // FORM-001: Widgets exist but no AcroForm at all
        if (widgetCount > 0 && acroForm == null) {
            issues.add(new ValidationIssue(
                    "WARNING", "FORM-001",
                    widgetCount + " form field widget(s) detected on page(s) but document has no AcroForm — fields cannot be read or filled",
                    "PDF 2.0, Section 12.7.2",
                    "Document Catalog",
                    "form"));
            return;
        }

        if (acroForm == null) return;

        if (needAppearancesTrue) {
            issues.add(new ValidationIssue(
                    "WARNING", "FORM-003",
                    "/NeedAppearances is true — viewers will regenerate appearance streams on open, which can destroy existing signature visuals and cause rendering inconsistencies across viewers",
                    "PDF 2.0, Section 12.7.2 Table 225",
                    "AcroForm",
                    "form"));
        }

        boolean hasFields = !acroForm.getFields().isEmpty();

        // FORM-002: AcroForm /Fields empty but widgets on pages
        if (!hasFields && widgetCount > 0) {
            issues.add(new ValidationIssue(
                    "WARNING", "FORM-002",
                    "AcroForm exists but declares no fields, yet " + widgetCount + " widget annotation(s) were found — fields are orphaned",
                    "PDF 2.0, Section 12.7.3",
                    "AcroForm",
                    "form"));
        }

        // FORM-007: XFA present alongside AcroForm
        COSBase xfa = acroForm.getCOSObject().getDictionaryObject(COSName.getPDFName("XFA"));
        if (xfa != null) {
            issues.add(new ValidationIssue(
                    "WARNING", "FORM-007",
                    "Document contains an XFA form definition alongside AcroForm — field values may disagree between the two and signing behavior depends on which layer the viewer uses. XFA was deprecated in PDF 2.0",
                    "PDF 2.0, Section 12.7.8 (deprecated)",
                    "AcroForm",
                    "form"));
        }

        if (!hasFields) return;

        // FORM-004: Field hierarchy parent↔child inconsistency
        validateFieldHierarchy(acroForm, issues);

        // FORM-005: SigFlags not set or wrong when signature fields exist
        validateSigFlags(acroForm, issues);

        // FORM-006: Orphaned widgets / fields (widget missing from page /Annots or not linked to AcroForm)
        validateFieldWidgetLinkage(acroForm, pageWidgetDicts, issues);
    }

    private void validateFieldHierarchy(PDAcroForm acroForm, List<ValidationIssue> issues) {
        try {
            for (PDField field : acroForm.getFieldTree()) {
                COSDictionary fieldDict = field.getCOSObject();
                COSBase parentRef = fieldDict.getDictionaryObject(COSName.PARENT);
                if (!(parentRef instanceof COSDictionary parentDict)) continue;

                COSBase kidsBase = parentDict.getDictionaryObject(COSName.KIDS);
                if (!(kidsBase instanceof COSArray kidsArr)) {
                    issues.add(new ValidationIssue(
                            "WARNING", "FORM-004",
                            "Field '" + field.getFullyQualifiedName() + "' has a /Parent with no /Kids array — broken hierarchy",
                            "PDF 2.0, Section 12.7.3.1",
                            "Field: " + field.getFullyQualifiedName(),
                            "form"));
                    continue;
                }

                boolean found = false;
                for (int i = 0; i < kidsArr.size(); i++) {
                    if (kidsArr.getObject(i) == fieldDict) { found = true; break; }
                }
                if (!found) {
                    issues.add(new ValidationIssue(
                            "WARNING", "FORM-004",
                            "Field '" + field.getFullyQualifiedName() + "' references a /Parent that does not list it in /Kids — hierarchy is inconsistent",
                            "PDF 2.0, Section 12.7.3.1",
                            "Field: " + field.getFullyQualifiedName(),
                            "form"));
                }
            }
        } catch (Exception e) {
            log.debug("Error validating field hierarchy", e);
        }
    }

    private void validateSigFlags(PDAcroForm acroForm, List<ValidationIssue> issues) {
        boolean hasSigField = false;
        try {
            for (PDField field : acroForm.getFieldTree()) {
                if (field instanceof PDSignatureField) { hasSigField = true; break; }
            }
        } catch (Exception e) {
            log.debug("Error scanning for signature fields", e);
        }

        if (!hasSigField && !acroForm.isSignaturesExist()) return;

        boolean sigExist = acroForm.isSignaturesExist();
        boolean appendOnly = acroForm.isAppendOnly();

        if (!sigExist && !appendOnly) {
            issues.add(new ValidationIssue(
                    "WARNING", "FORM-005",
                    "/SigFlags is missing or 0 despite signature fields being present — viewers may not recognize signatures and subsequent saves could destroy them",
                    "PDF 2.0, Section 12.7.2 Table 225",
                    "AcroForm",
                    "form"));
        } else if (!appendOnly) {
            issues.add(new ValidationIssue(
                    "WARNING", "FORM-005",
                    "/SigFlags bit 2 (AppendOnly) is not set — a non-incremental save will invalidate all existing signatures",
                    "PDF 2.0, Section 12.7.2 Table 225",
                    "AcroForm",
                    "form"));
        }
    }

    private void validateFieldWidgetLinkage(PDAcroForm acroForm, Set<COSDictionary> pageWidgetDicts,
                                            List<ValidationIssue> issues) {
        try {
            Set<COSDictionary> fieldWidgetDicts = new HashSet<>();
            for (PDField field : acroForm.getFieldTree()) {
                for (var widget : field.getWidgets()) {
                    COSDictionary wDict = widget.getCOSObject();
                    fieldWidgetDicts.add(wDict);
                    if (!pageWidgetDicts.contains(wDict)) {
                        issues.add(new ValidationIssue(
                                "WARNING", "FORM-006",
                                "Field '" + field.getFullyQualifiedName() + "' has a widget not present in any page's /Annots — it will be invisible and non-interactive",
                                "PDF 2.0, Section 12.7.3.1",
                                "Field: " + field.getFullyQualifiedName(),
                                "form"));
                        break;
                    }
                }
            }

            int orphaned = 0;
            for (COSDictionary pw : pageWidgetDicts) {
                if (!fieldWidgetDicts.contains(pw)) orphaned++;
            }
            if (orphaned > 0) {
                issues.add(new ValidationIssue(
                        "WARNING", "FORM-006",
                        orphaned + " widget annotation(s) on pages are not linked to any AcroForm field — they appear as form fields visually but are non-functional",
                        "PDF 2.0, Section 12.7.3.1",
                        "Page annotations",
                        "form"));
            }
        } catch (Exception e) {
            log.debug("Error validating field-widget linkage", e);
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
