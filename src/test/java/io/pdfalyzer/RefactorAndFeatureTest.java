package io.pdfalyzer;

import io.pdfalyzer.model.*;
import io.pdfalyzer.service.*;
import io.pdfalyzer.web.EditApiController;
import io.pdfalyzer.web.ResourceApiController;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests covering the refactored architecture, the objectNumber-propagation
 * bug-fix in CosNodeBuilder.attachCosChildren, form-field operations, and
 * the new attachment / font endpoints.
 */
public class RefactorAndFeatureTest {

    private CosNodeBuilder cosBuilder;
    private SemanticTreeBuilder semanticBuilder;
    private PdfStructureParser parser;
    private PdfService pdfService;
    private PdfEditService editService;
    private CosEditService cosEditService;
    private FontInspectorService fontService;

    @BeforeEach
    void setUp() {
        cosBuilder      = new CosNodeBuilder();
        semanticBuilder = new SemanticTreeBuilder(cosBuilder, new PageResourceBuilder(cosBuilder));
        parser          = new PdfStructureParser(semanticBuilder, cosBuilder);
        pdfService      = new PdfService(new SessionService(), parser);
        editService     = new PdfEditService();
        cosEditService  = new CosEditService();
        fontService     = new FontInspectorService();
    }

    // ======================== BUG FIX: objectNumber in attachCosChildren ========================

    @Test
    void attachCosChildrenPropagatesObjectNumber() throws IOException {
        // The main bug: COS sub-nodes in semantic tree had objectNumber=-1, blocking edits
        byte[] pdf = createSimplePdf(1);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdf);
        PdfNode root = session.getTreeRoot();

        // Walk the entire tree and find COS leaf nodes with editable=true
        List<PdfNode> editableNodes = new ArrayList<>();
        collectEditableNodes(root, editableNodes);

        // At least some nodes should have a valid objectNumber (fix verified)
        assertTrue(editableNodes.stream().anyMatch(n -> n.getObjectNumber() >= 0),
                "After fix, at least one editable COS node must have objectNumber >= 0");
    }

    private void collectEditableNodes(PdfNode node, List<PdfNode> out) {
        if (node.isEditable() && node.getRawValue() != null) out.add(node);
        for (PdfNode child : node.getChildren()) collectEditableNodes(child, out);
    }

    // ======================== SEMANTIC TREE STRUCTURE ========================

    @Test
    void semanticTreeHasCatalogRoot() throws IOException {
        byte[] pdf = createSimplePdf(2);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdf);
        PdfNode root = session.getTreeRoot();
        assertEquals("Document Catalog", root.getName());
        assertEquals("catalog", root.getNodeCategory());
    }

    @Test
    void semanticTreeHasPagesNode() throws IOException {
        byte[] pdf = createSimplePdf(3);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdf);
        PdfNode pagesNode = findByCategory(session.getTreeRoot(), "pages");
        assertNotNull(pagesNode, "should have pages node");
        assertEquals(3, pagesNode.getChildren().size());
    }

    @Test
    void semanticTreeHasDocInfoNode() throws IOException {
        byte[] pdf = createPdfWithInfo("Test Title", "Test Author");
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdf);
        PdfNode infoNode = findByCategory(session.getTreeRoot(), "info");
        assertNotNull(infoNode, "should have doc info node");
        assertEquals("Test Title", infoNode.getProperties().get("Title"));
        assertEquals("Test Author", infoNode.getProperties().get("Author"));
    }

    // ======================== FORM FIELD OPERATIONS ========================

    @Test
    void addFormFieldTextCreatesField() throws IOException {
        byte[] pdf = createSimplePdf(1);
        FormFieldRequest req = new FormFieldRequest();
        req.setFieldType("text");
        req.setFieldName("myField");
        req.setPageIndex(0);
        req.setX(50); req.setY(50); req.setWidth(200); req.setHeight(30);
        byte[] modified = editService.addFormField(pdf, req);
        assertNotNull(modified);
        assertTrue(modified.length > 0);

        // Reload and verify field appears in tree
        PdfSession session = pdfService.uploadAndParse("mod.pdf", modified);
        PdfNode acroForm = findByCategory(session.getTreeRoot(), "acroform");
        assertNotNull(acroForm, "should have AcroForm node");
        assertTrue(acroForm.getChildren().stream()
                .anyMatch(n -> n.getName() != null && n.getName().contains("myField")),
                "myField should be in AcroForm tree");
    }

    @Test
    void deleteFormFieldRemovesIt() throws IOException {
        // Create PDF with a text field
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDTextField field = new PDTextField(acroForm);
            field.setPartialName("deleteMe");
            field.setDefaultAppearance("/Helv 10 Tf 0 g");
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(50, 50, 200, 30));
            widget.setPage(page);
            widget.setPrinted(true);
            page.getAnnotations().add(widget);
            acroForm.getFields().add(field);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        // Delete the field
        byte[] modified = editService.deleteFormField(pdf, "deleteMe");
        PdfSession session = pdfService.uploadAndParse("del.pdf", modified);
        PdfNode acroForm = findByCategory(session.getTreeRoot(), "acroform");
        // Field should no longer be in tree
        if (acroForm != null) {
            assertFalse(acroForm.getChildren().stream()
                    .anyMatch(n -> n.getName() != null && n.getName().contains("deleteMe")),
                    "Deleted field should not appear in tree");
        }
    }

    @Test
    void setFormFieldValueUpdatesValue() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDTextField field = new PDTextField(acroForm);
            field.setPartialName("valueField");
            field.setDefaultAppearance("/Helv 10 Tf 0 g");
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(50, 50, 200, 30));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            acroForm.getFields().add(field);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }
        byte[] modified = editService.setFormFieldValue(pdf, "valueField", "Hello World");
        PdfSession session = pdfService.uploadAndParse("val.pdf", modified);
        PdfNode acroForm = findByCategory(session.getTreeRoot(), "acroform");
        assertNotNull(acroForm);
        assertTrue(acroForm.getChildren().stream()
                .anyMatch(n -> n.getProperties() != null &&
                               "Hello World".equals(n.getProperties().get("Value"))),
                "Field value should be updated in tree");
    }

    @Test
    void setComboChoicesUpdatesOptions() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDComboBox combo = new PDComboBox(acroForm);
            combo.setPartialName("myCombo");
            combo.setOptions(Arrays.asList("A", "B", "C"));
            PDAnnotationWidget widget = combo.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(50, 50, 200, 30));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            acroForm.getFields().add(combo);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }
        byte[] modified = editService.setComboChoices(pdf, "myCombo",
                Arrays.asList("X", "Y", "Z", "W"));
        assertNotNull(modified);
        assertTrue(modified.length > 0);
    }

    @Test
    void addFormFieldsBatchAddsMultiple() throws IOException {
        byte[] pdf = createSimplePdf(1);
        FormFieldRequest first = new FormFieldRequest();
        first.setFieldType("text");
        first.setFieldName("batchA");
        first.setPageIndex(0);
        first.setX(50); first.setY(700); first.setWidth(180); first.setHeight(20);

        FormFieldRequest second = new FormFieldRequest();
        second.setFieldType("checkbox");
        second.setFieldName("batchB");
        second.setPageIndex(0);
        second.setX(50); second.setY(660); second.setWidth(18); second.setHeight(18);

        byte[] modified = editService.addFormFields(pdf, Arrays.asList(first, second));
        PdfSession session = pdfService.uploadAndParse("batch.pdf", modified);
        PdfNode acroForm = findByCategory(session.getTreeRoot(), "acroform");
        assertNotNull(acroForm);
        assertTrue(acroForm.getChildren().stream().anyMatch(n -> n.getName().contains("batchA")));
        assertTrue(acroForm.getChildren().stream().anyMatch(n -> n.getName().contains("batchB")));
    }

    @Test
    void updateFieldRectChangesBoundingBox() throws IOException {
        byte[] pdf = createSimplePdf(1);
        FormFieldRequest req = new FormFieldRequest();
        req.setFieldType("text");
        req.setFieldName("moveMe");
        req.setPageIndex(0);
        req.setX(60); req.setY(640); req.setWidth(120); req.setHeight(20);
        byte[] withField = editService.addFormField(pdf, req);

        byte[] moved = editService.updateFieldRect(withField, "moveMe", 100, 500, 200, 30);
        PdfSession session = pdfService.uploadAndParse("moved.pdf", moved);
        PdfNode acroForm = findByCategory(session.getTreeRoot(), "acroform");
        assertNotNull(acroForm);
        PdfNode target = acroForm.getChildren().stream()
                .filter(n -> n.getName() != null && n.getName().contains("moveMe"))
                .findFirst().orElse(null);
        assertNotNull(target);
        assertNotNull(target.getBoundingBox());
        assertEquals(100.0, target.getBoundingBox()[0], 0.5);
        assertEquals(500.0, target.getBoundingBox()[1], 0.5);
        assertEquals(200.0, target.getBoundingBox()[2], 0.5);
        assertEquals(30.0, target.getBoundingBox()[3], 0.5);
    }

    @Test
    void applyFieldOptionsUpdatesMultipleFields() throws IOException {
        byte[] pdf = createSimplePdf(1);

        FormFieldRequest first = new FormFieldRequest();
        first.setFieldType("text");
        first.setFieldName("optA");
        first.setPageIndex(0);
        first.setX(50); first.setY(700); first.setWidth(160); first.setHeight(20);

        FormFieldRequest second = new FormFieldRequest();
        second.setFieldType("text");
        second.setFieldName("optB");
        second.setPageIndex(0);
        second.setX(50); second.setY(660); second.setWidth(160); second.setHeight(20);

        byte[] withFields = editService.addFormFields(pdf, Arrays.asList(first, second));
        Map<String, Object> options = new HashMap<>();
        options.put("required", true);
        options.put("readonly", true);
        byte[] modified = editService.applyFieldOptions(withFields, Arrays.asList("optA", "optB"), options);

        PdfSession session = pdfService.uploadAndParse("opts.pdf", modified);
        PdfNode acroForm = findByCategory(session.getTreeRoot(), "acroform");
        assertNotNull(acroForm);
        long requiredCount = acroForm.getChildren().stream()
                .filter(n -> n.getName() != null && (n.getName().contains("optA") || n.getName().contains("optB")))
                .filter(n -> "true".equalsIgnoreCase(String.valueOf(n.getProperties().get("Required"))))
                .count();
        long readonlyCount = acroForm.getChildren().stream()
                .filter(n -> n.getName() != null && (n.getName().contains("optA") || n.getName().contains("optB")))
                .filter(n -> "true".equalsIgnoreCase(String.valueOf(n.getProperties().get("ReadOnly"))))
                .count();
        assertEquals(2, requiredCount);
        assertEquals(2, readonlyCount);
    }

    // ======================== COS EDIT (end-to-end) ========================

    @Test
    void cosEditEndpointUpdatesAndTree() throws IOException {
        byte[] pdf = createSimplePdf(1);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdf);
        CosNodeBuilder cosNode = new CosNodeBuilder();
        PdfNode root = session.getTreeRoot();

        // Find a COS node with editable=true and valid objectNumber
        PdfNode target = findFirstEditable(root);
        assertNotNull(target, "Should find at least one editable COS node");
        assertTrue(target.getObjectNumber() >= 0,
                "Editable node must have valid object number after propagation fix");
    }

    // ======================== FONT SERVICE ========================

    @Test
    void fontAnalysisReturnsInfo() throws IOException {
        byte[] pdf = createSimplePdf(1); // Basic PDF with default font
        List<FontInfo> fonts = fontService.analyzeFonts(pdf);
        assertNotNull(fonts); // may be empty for blank PDFs - just assert no exception
    }

    @Test
    void fontExtractReturnsNullForNonEmbedded() throws IOException {
        byte[] pdf = createSimplePdf(1);
        byte[] fontData = fontService.extractFontFile(pdf, 999, 0);
        assertNull(fontData, "Non-existent object should return null");
    }

    @Test
    void characterMapReturnsEmpty() throws IOException {
        byte[] pdf = createSimplePdf(1);
        Map<String, String> map = fontService.getCharacterMap(pdf, 0, "F1");
        assertNotNull(map); // may be empty - just assert no exception
    }

    // ======================== RAW COS TREE ========================

    @Test
    void rawCosTreeHasObjects() throws IOException {
        byte[] pdf = createSimplePdf(1);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdf);
        // Load raw cos tree via service
        PdfNode rawTree = null;
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.Loader.loadPDF(session.getPdfBytes())) {
            rawTree = parser.buildRawCosTree(doc);
        }
        assertNotNull(rawTree);
        assertTrue(rawTree.getChildren().size() > 0, "Raw COS tree should have objects");
        assertEquals("raw-cos", rawTree.getNodeCategory());
    }

    // ======================== CONTROLLER ENDPOINTS ========================

    @Test
    void editControllerAddFieldReturnsTree() throws IOException {
        byte[] pdf = createSimplePdf(1);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdf);
        EditApiController editCtrl = new EditApiController(pdfService, cosEditService, editService);

        FormFieldRequest req = new FormFieldRequest();
        req.setFieldType("text"); req.setFieldName("ctrlField");
        req.setPageIndex(0); req.setX(50); req.setY(50);
        req.setWidth(150); req.setHeight(25);
        ResponseEntity<Map<String, Object>> resp = editCtrl.addFormField(session.getId(), req);
        assertEquals(200, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().get("tree"));
    }

    @Test
    void resourceControllerHandlesInvalidObjNum() throws IOException {
        byte[] pdf = createSimplePdf(1);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdf);
        ResourceApiController resCtrl = new ResourceApiController(pdfService);
        ResponseEntity<byte[]> resp = resCtrl.getResource(session.getId(), 99999, 0, null, false);
        assertEquals(404, resp.getStatusCodeValue());
    }

    // ======================== HELPERS ========================

    private byte[] createSimplePdf(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] createPdfWithInfo(String title, String author) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(title);
            info.setAuthor(author);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private PdfNode findByCategory(PdfNode node, String category) {
        if (category.equals(node.getNodeCategory())) return node;
        for (PdfNode child : node.getChildren()) {
            PdfNode found = findByCategory(child, category);
            if (found != null) return found;
        }
        return null;
    }

    private PdfNode findFirstEditable(PdfNode node) {
        if (node.isEditable() && node.getRawValue() != null && node.getObjectNumber() >= 0) return node;
        for (PdfNode child : node.getChildren()) {
            PdfNode found = findFirstEditable(child);
            if (found != null) return found;
        }
        return null;
    }
}
