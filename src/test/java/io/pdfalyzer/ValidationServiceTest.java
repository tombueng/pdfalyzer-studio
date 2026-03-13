package io.pdfalyzer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.pdfalyzer.model.ValidationIssue;
import io.pdfalyzer.service.ValidationService;

import static org.junit.jupiter.api.Assertions.*;

class ValidationServiceTest {

    private ValidationService validationService;

    @BeforeEach
    void setUp() {
        validationService = new ValidationService();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private byte[] createSimplePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] createPdfWithTextField() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDTextField field = new PDTextField(acroForm);
            field.setPartialName("testField");
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(10, 10, 200, 20));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            acroForm.getFields().add(field);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private List<String> ruleIds(List<ValidationIssue> issues) {
        return issues.stream().map(ValidationIssue::getRuleId).toList();
    }

    private boolean hasRule(List<ValidationIssue> issues, String ruleId) {
        return ruleIds(issues).contains(ruleId);
    }

    // ── META tests ──────────────────────────────────────────────────────────

    @Test
    void simplePdfReportsMetadataIssues() throws IOException {
        List<ValidationIssue> issues = validationService.validate(createSimplePdf());
        assertTrue(hasRule(issues, "META-001"), "should report missing XMP metadata");
        assertTrue(hasRule(issues, "META-002"), "should report missing title");
        assertTrue(hasRule(issues, "META-003"), "should report missing producer");
    }

    // ── FONT tests ──────────────────────────────────────────────────────────

    @Test
    void pdfWithStandardFontReportsNotEmbedded() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Hello");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FONT-001"), "should report non-embedded font");
        assertTrue(hasRule(issues, "FONT-002"), "should report missing ToUnicode CMap");
    }

    // ── ANNOT tests ─────────────────────────────────────────────────────────

    @Test
    void annotationWithoutAppearanceReportsAnno002() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // Raw widget annotation with no /AP
            COSDictionary annotDict = new COSDictionary();
            annotDict.setName(COSName.SUBTYPE, "Widget");
            annotDict.setItem(COSName.RECT, new COSArray());
            page.getCOSObject().setItem(COSName.ANNOTS,
                    new COSArray(List.of(annotDict)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "ANNOT-002"), "should report missing appearance stream");
    }

    // ── FORM-001: Widgets on pages but no AcroForm ──────────────────────────

    @Test
    void widgetsWithoutAcroFormReportsForm001() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // Add a widget annotation directly to the page (no AcroForm)
            COSDictionary widgetDict = new COSDictionary();
            widgetDict.setName(COSName.SUBTYPE, "Widget");
            widgetDict.setItem(COSName.T, new COSString("orphanField"));
            widgetDict.setItem(COSName.RECT, new COSArray());
            COSArray annots = new COSArray();
            annots.add(widgetDict);
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            // Explicitly ensure no AcroForm
            doc.getDocumentCatalog().getCOSObject().removeItem(COSName.ACRO_FORM);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FORM-001"), "should detect widgets without AcroForm");
        // FORM-002..007 should NOT fire (no AcroForm → early return)
        assertFalse(hasRule(issues, "FORM-002"));
        assertFalse(hasRule(issues, "FORM-003"));
    }

    // ── FORM-002: AcroForm with empty /Fields but widgets on pages ──────────

    @Test
    void emptyAcroFormFieldsWithWidgetsReportsForm002() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // Create AcroForm with empty /Fields
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            // Add widget directly to page annotations (not linked to AcroForm)
            COSDictionary widgetDict = new COSDictionary();
            widgetDict.setName(COSName.SUBTYPE, "Widget");
            widgetDict.setItem(COSName.T, new COSString("orphan"));
            widgetDict.setItem(COSName.RECT, new COSArray());
            COSArray annots = new COSArray();
            annots.add(widgetDict);
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FORM-002"), "should detect empty AcroForm /Fields with page widgets");
    }

    // ── FORM-003: NeedAppearances is true ───────────────────────────────────

    @Test
    void needAppearancesTrueReportsForm003() throws IOException {
        // PDFBox forces NeedAppearances=false during save (it generates appearances).
        // Build a normal PDF with AcroForm, then patch the raw bytes to flip the flag.
        byte[] pdfBytes = createPdfWithTextField();
        String raw = new String(pdfBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        // Replace "/NeedAppearances false" with "/NeedAppearances true " (trailing space keeps byte count)
        String patched = raw.replace("/NeedAppearances false", "/NeedAppearances true ");
        assertNotEquals(raw, patched, "PDF should contain /NeedAppearances false to patch");
        pdfBytes = patched.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FORM-003"), "should detect NeedAppearances=true");
    }

    @Test
    void needAppearancesFalseDoesNotReportForm003() throws IOException {
        byte[] pdfBytes = createPdfWithTextField();
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertFalse(hasRule(issues, "FORM-003"), "should not report FORM-003 when NeedAppearances is false");
    }

    // ── FORM-004: Field hierarchy inconsistency ─────────────────────────────

    @Test
    void brokenParentChildHierarchyReportsForm004() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            // Create a parent field and a child field
            PDTextField parent = new PDTextField(acroForm);
            parent.setPartialName("parent");
            PDTextField child = new PDTextField(acroForm);
            child.setPartialName("child");
            // Set child's /Parent to point to parent's dict
            child.getCOSObject().setItem(COSName.PARENT, parent.getCOSObject());
            // But do NOT add child to parent's /Kids array → hierarchy is broken
            // Add child widget to page so it's reachable
            PDAnnotationWidget childWidget = child.getWidgets().get(0);
            childWidget.setRectangle(new PDRectangle(10, 10, 100, 20));
            childWidget.setPage(page);
            page.getAnnotations().add(childWidget);
            // Add both to AcroForm /Fields (child is directly in /Fields but also claims parent)
            acroForm.getFields().add(parent);
            acroForm.getFields().add(child);
            // Add parent widget to page too
            PDAnnotationWidget parentWidget = parent.getWidgets().get(0);
            parentWidget.setRectangle(new PDRectangle(10, 40, 100, 20));
            parentWidget.setPage(page);
            page.getAnnotations().add(parentWidget);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FORM-004"), "should detect broken parent/child hierarchy");
    }

    @Test
    void validHierarchyDoesNotReportForm004() throws IOException {
        byte[] pdfBytes = createPdfWithTextField();
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertFalse(hasRule(issues, "FORM-004"), "should not report FORM-004 for a well-formed field");
    }

    // ── FORM-005: SigFlags not set or wrong ─────────────────────────────────

    @Test
    void sigFieldWithoutSigFlagsReportsForm005() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            // Do NOT set SigFlags
            PDSignatureField sigField = new PDSignatureField(acroForm);
            sigField.setPartialName("sig");
            PDAnnotationWidget widget = sigField.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(10, 10, 200, 50));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            acroForm.getFields().add(sigField);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FORM-005"), "should detect missing SigFlags with signature field present");
        // Check it mentions SigFlags
        ValidationIssue form005 = issues.stream()
                .filter(i -> "FORM-005".equals(i.getRuleId())).findFirst().orElseThrow();
        assertTrue(form005.getMessage().contains("SigFlags"));
    }

    @Test
    void sigFieldWithSigFlagsOneReportsForm005AppendOnly() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            // Set SigFlags to 1 (SignaturesExist) but NOT 2 (AppendOnly)
            acroForm.getCOSObject().setInt(COSName.SIG_FLAGS, 1);
            PDSignatureField sigField = new PDSignatureField(acroForm);
            sigField.setPartialName("sig");
            PDAnnotationWidget widget = sigField.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(10, 10, 200, 50));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            acroForm.getFields().add(sigField);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FORM-005"), "should detect missing AppendOnly bit");
        ValidationIssue form005 = issues.stream()
                .filter(i -> "FORM-005".equals(i.getRuleId())).findFirst().orElseThrow();
        assertTrue(form005.getMessage().contains("AppendOnly"));
    }

    @Test
    void sigFieldWithSigFlagsThreeDoesNotReportForm005() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            // Set SigFlags to 3 (SignaturesExist + AppendOnly) — correct value
            acroForm.getCOSObject().setInt(COSName.SIG_FLAGS, 3);
            PDSignatureField sigField = new PDSignatureField(acroForm);
            sigField.setPartialName("sig");
            PDAnnotationWidget widget = sigField.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(10, 10, 200, 50));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            acroForm.getFields().add(sigField);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertFalse(hasRule(issues, "FORM-005"), "should not report FORM-005 when SigFlags=3");
    }

    @Test
    void noSigFieldDoesNotReportForm005() throws IOException {
        byte[] pdfBytes = createPdfWithTextField();
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertFalse(hasRule(issues, "FORM-005"), "should not report FORM-005 when no signature fields exist");
    }

    // ── FORM-006: Orphaned widget-field linkage ─────────────────────────────

    @Test
    void fieldWidgetNotOnPageReportsForm006() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDTextField field = new PDTextField(acroForm);
            field.setPartialName("ghost");
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(10, 10, 200, 20));
            // Do NOT add widget to page annotations → invisible field
            acroForm.getFields().add(field);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FORM-006"),
                "should detect field whose widget is not in any page /Annots");
    }

    @Test
    void pageWidgetNotInAcroFormReportsForm006() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            // Add a real field (so hasFields=true and we reach FORM-006 logic)
            PDTextField realField = new PDTextField(acroForm);
            realField.setPartialName("real");
            PDAnnotationWidget realWidget = realField.getWidgets().get(0);
            realWidget.setRectangle(new PDRectangle(10, 10, 200, 20));
            realWidget.setPage(page);
            page.getAnnotations().add(realWidget);
            acroForm.getFields().add(realField);
            // Add an extra orphan widget to the page that is NOT in AcroForm /Fields
            COSDictionary orphanDict = new COSDictionary();
            orphanDict.setName(COSName.SUBTYPE, "Widget");
            orphanDict.setItem(COSName.T, new COSString("orphan"));
            orphanDict.setItem(COSName.RECT, new COSArray());
            page.getCOSObject().getCOSArray(COSName.ANNOTS).add(orphanDict);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FORM-006"),
                "should detect page widget not linked to any AcroForm field");
    }

    @Test
    void properlyLinkedFieldDoesNotReportForm006() throws IOException {
        byte[] pdfBytes = createPdfWithTextField();
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertFalse(hasRule(issues, "FORM-006"), "should not report FORM-006 for properly linked field");
    }

    // ── FORM-007: XFA present ───────────────────────────────────────────────

    @Test
    void xfaPresentReportsForm007() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            // Add a dummy XFA entry
            acroForm.getCOSObject().setItem(COSName.getPDFName("XFA"),
                    new COSString("<xdp:xdp/>"));
            PDTextField field = new PDTextField(acroForm);
            field.setPartialName("xfaField");
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(10, 10, 200, 20));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            acroForm.getFields().add(field);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertTrue(hasRule(issues, "FORM-007"), "should detect XFA alongside AcroForm");
    }

    @Test
    void noXfaDoesNotReportForm007() throws IOException {
        byte[] pdfBytes = createPdfWithTextField();
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        assertFalse(hasRule(issues, "FORM-007"), "should not report FORM-007 when no XFA present");
    }

    // ── Combined / sanity ───────────────────────────────────────────────────

    @Test
    void wellFormedPdfWithFieldReportsNoFormIssues() throws IOException {
        byte[] pdfBytes = createPdfWithTextField();
        List<ValidationIssue> issues = validationService.validate(pdfBytes);
        List<String> formRules = issues.stream()
                .map(ValidationIssue::getRuleId)
                .filter(r -> r.startsWith("FORM-"))
                .toList();
        assertTrue(formRules.isEmpty(),
                "well-formed PDF with properly linked field should have no FORM issues, got: " + formRules);
    }
}
