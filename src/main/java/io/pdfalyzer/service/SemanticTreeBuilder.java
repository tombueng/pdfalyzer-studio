package io.pdfalyzer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.stereotype.Component;

import io.pdfalyzer.model.PdfNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the high-level semantic tree for a PDF document.
 * Delegates COS subtree construction to {@link CosNodeBuilder} and
 * per-page resource (fonts/images) construction to {@link PageResourceBuilder}.
 */
@Component
@Slf4j
public class SemanticTreeBuilder {

    private final CosNodeBuilder cosBuilder;
    private final PageResourceBuilder pageResourceBuilder;

    public SemanticTreeBuilder(CosNodeBuilder cosBuilder, PageResourceBuilder pageResourceBuilder) {
        this.cosBuilder = cosBuilder;
        this.pageResourceBuilder = pageResourceBuilder;
    }

    public PdfNode buildTree(PDDocument doc) {
        CosNodeBuilder.ParseContext ctx = new CosNodeBuilder.ParseContext(doc, 8, false);

        PdfNode root = new PdfNode("catalog", "Document Catalog", "root", "fa-book", "#dc3545");
        root.setNodeCategory("catalog");

        try {
            cosBuilder.attachCosChildren(root, doc.getDocumentCatalog().getCOSObject(),
                    "catalog-cos", ctx, 0);
        } catch (Exception e) {
            log.warn("Error attaching COS to catalog", e);
        }

        PDDocumentInformation info = doc.getDocumentInformation();
        if (info != null)
            root.addChild(buildDocInfoNode(info, ctx));

        root.addChild(buildPagesTree(doc, ctx));

        try {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null)
                root.addChild(buildAcroFormTree(acroForm, ctx));
        } catch (Exception e) {
            log.warn("Error parsing AcroForm", e);
        }

        try {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null)
                root.addChild(buildOutlineTree(outline, ctx));
        } catch (Exception e) {
            log.warn("Error parsing outlines", e);
        }

        try {
            PdfNode attachmentsNode = buildAttachmentsNode(doc, ctx);
            if (attachmentsNode != null)
                root.addChild(attachmentsNode);
        } catch (Exception e) {
            log.warn("Error parsing attachments", e);
        }

        return root;
    }

    // ======================== DOC INFO ========================

    private PdfNode buildDocInfoNode(PDDocumentInformation info, CosNodeBuilder.ParseContext ctx) {
        PdfNode node = new PdfNode("docinfo", "Document Info", "info", "fa-info-circle", "#17a2b8");
        node.setNodeCategory("info");
        if (info.getTitle() != null)
            node.addProperty("Title", info.getTitle());
        if (info.getAuthor() != null)
            node.addProperty("Author", info.getAuthor());
        if (info.getSubject() != null)
            node.addProperty("Subject", info.getSubject());
        if (info.getCreator() != null)
            node.addProperty("Creator", info.getCreator());
        if (info.getProducer() != null)
            node.addProperty("Producer", info.getProducer());
        if (info.getCreationDate() != null)
            node.addProperty("Created", info.getCreationDate().getTime().toString());
        if (info.getModificationDate() != null)
            node.addProperty("Modified", info.getModificationDate().getTime().toString());
        try {
            cosBuilder.attachCosChildren(node, info.getCOSObject(), "docinfo-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to doc info", e);
        }
        return node;
    }

    // ======================== PAGES ========================

    private PdfNode buildPagesTree(PDDocument doc, CosNodeBuilder.ParseContext ctx) {
        PdfNode pagesNode = new PdfNode("pages", "Pages (" + doc.getNumberOfPages() + ")",
                "folder", "fa-copy", "#ffc107");
        pagesNode.setNodeCategory("pages");
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            try {
                pagesNode.addChild(buildPageNode(doc.getPage(i), i, ctx));
            } catch (Exception e) {
                log.warn("Error parsing page {}", i, e);
                PdfNode err = new PdfNode("page-" + i, "Page " + (i + 1) + " (error)",
                        "page", "fa-exclamation-triangle", "#dc3545");
                pagesNode.addChild(err);
            }
        }
        return pagesNode;
    }

    private PdfNode buildPageNode(PDPage page, int index, CosNodeBuilder.ParseContext ctx) {
        PDRectangle mediaBox = page.getMediaBox();
        String dims = String.format("%.0f x %.0f", mediaBox.getWidth(), mediaBox.getHeight());
        PdfNode pageNode = new PdfNode("page-" + index, "Page " + (index + 1),
                "page", "fa-file-alt", "#6c757d");
        pageNode.setNodeCategory("page");
        pageNode.setPageIndex(index);
        pageNode.addProperty("MediaBox", dims);
        pageNode.addProperty("Rotation", String.valueOf(page.getRotation()));
        if (page.getCropBox() != null && !page.getCropBox().equals(mediaBox)) {
            pageNode.addProperty("CropBox",
                    String.format("%.0f x %.0f", page.getCropBox().getWidth(), page.getCropBox().getHeight()));
        }

        try {
            PDResources resources = page.getResources();
            if (resources != null) {
                PdfNode resourcesNode = new PdfNode("page-" + index + "-resources",
                        "Resources", "folder", "fa-cubes", "#6f42c1");
                resourcesNode.setNodeCategory("resources");
                resourcesNode.setPageIndex(index);
                PdfNode fontsFolder = buildPageFontsNode(resources, index, ctx);
                if (fontsFolder != null)
                    resourcesNode.addChild(fontsFolder);
                PdfNode imagesFolder = buildPageImagesNode(page, resources, index, ctx);
                if (imagesFolder != null)
                    resourcesNode.addChild(imagesFolder);
                try {
                    COSDictionary resDict = resources.getCOSObject();
                    if (resDict != null) {
                        cosBuilder.attachCosChildren(resourcesNode, resDict,
                                "page-" + index + "-resources-cos", ctx, 0);
                    }
                } catch (Exception e) {
                    log.debug("Error attaching COS to resources for page {}", index, e);
                }
                if (!resourcesNode.getChildren().isEmpty())
                    pageNode.addChild(resourcesNode);
            }
        } catch (Exception e) {
            log.warn("Error parsing resources for page {}", index, e);
        }

        try {
            List<PDAnnotation> annotations = page.getAnnotations();
            if (annotations != null && !annotations.isEmpty()) {
                PdfNode annotsNode = new PdfNode("page-" + index + "-annots",
                        "Annotations (" + annotations.size() + ")",
                        "folder", "fa-sticky-note", "#fd7e14");
                annotsNode.setNodeCategory("annotations");
                annotsNode.setPageIndex(index);
                for (int a = 0; a < annotations.size(); a++) {
                    annotsNode.addChild(buildAnnotationNode(annotations.get(a), index, a, ctx));
                }
                pageNode.addChild(annotsNode);
            }
        } catch (Exception e) {
            log.warn("Error parsing annotations for page {}", index, e);
        }

        try {
            PdfNode csNode = cosBuilder.buildContentStreamNode(page, index, ctx);
            if (!csNode.getChildren().isEmpty())
                pageNode.addChild(csNode);
        } catch (Exception e) {
            log.warn("Error parsing content stream for page {}", index, e);
        }

        try {
            cosBuilder.attachCosChildren(pageNode, page.getCOSObject(),
                    "page-" + index + "-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to page {}", index, e);
        }

        return pageNode;
    }

    // ======================== FONTS (per page) ========================

    private PdfNode buildPageFontsNode(PDResources resources, int pageIndex,
            CosNodeBuilder.ParseContext ctx) {
        return pageResourceBuilder.buildPageFontsNode(resources, pageIndex, ctx);
    }

    // ======================== IMAGES (per page) ========================

    private PdfNode buildPageImagesNode(PDPage page, PDResources resources,
            int pageIndex, CosNodeBuilder.ParseContext ctx) {
        return pageResourceBuilder.buildPageImagesNode(page, resources, pageIndex, ctx);
    }

    // ======================== ANNOTATIONS ========================

    private PdfNode buildAnnotationNode(PDAnnotation annot, int pageIndex,
            int annotIndex, CosNodeBuilder.ParseContext ctx) {
        String subtype = annot.getSubtype();
        String label = (subtype != null ? subtype : "Unknown") + " Annotation";
        PdfNode node = new PdfNode("page-" + pageIndex + "-annot-" + annotIndex,
                label, "annotation", getAnnotationIcon(subtype), "#fd7e14");
        node.setNodeCategory("annotation");
        node.setPageIndex(pageIndex);
        if (subtype != null)
            node.addProperty("Subtype", subtype);
        if (annot.getContents() != null)
            node.addProperty("Contents", annot.getContents());
        if (annot.getAnnotationName() != null)
            node.addProperty("Name", annot.getAnnotationName());
        PDRectangle rect = annot.getRectangle();
        if (rect != null) {
            node.setBoundingBox(new double[] {
                    rect.getLowerLeftX(), rect.getLowerLeftY(),
                    rect.getWidth(), rect.getHeight() });
            node.addProperty("Rectangle", String.format("%.1f, %.1f, %.1f, %.1f",
                    rect.getLowerLeftX(), rect.getLowerLeftY(),
                    rect.getUpperRightX(), rect.getUpperRightY()));
        }
        try {
            cosBuilder.attachCosChildren(node, annot.getCOSObject(),
                    "page-" + pageIndex + "-annot-" + annotIndex + "-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to annotation", e);
        }
        return node;
    }

    private String getAnnotationIcon(String subtype) {
        if (subtype == null)
            return "fa-question-circle";
        switch (subtype) {
            case "Link":
                return "fa-link";
            case "Text":
                return "fa-comment";
            case "Widget":
                return "fa-cog";
            case "Highlight":
                return "fa-highlighter";
            case "Underline":
                return "fa-underline";
            case "StrikeOut":
                return "fa-strikethrough";
            case "Stamp":
                return "fa-stamp";
            case "FreeText":
                return "fa-pen";
            default:
                return "fa-sticky-note";
        }
    }

    // ======================== ACROFORM ========================

    private PdfNode buildAcroFormTree(PDAcroForm acroForm, CosNodeBuilder.ParseContext ctx) {
        List<PDField> fields = acroForm.getFields();
        PdfNode formNode = new PdfNode("acroform",
                "AcroForm (" + fields.size() + " fields)", "acroform", "fa-wpforms", "#0dcaf0");
        formNode.setNodeCategory("acroform");
        if (acroForm.isSignaturesExist())
            formNode.addProperty("SignaturesExist", "true");
        formNode.addProperty("NeedAppearances", String.valueOf(acroForm.getNeedAppearances()));
        for (PDField field : fields) {
            formNode.addChild(buildFieldNode(field, ctx));
        }
        try {
            cosBuilder.attachCosChildren(formNode, acroForm.getCOSObject(), "acroform-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to acroform", e);
        }
        return formNode;
    }

    private PdfNode buildFieldNode(PDField field, CosNodeBuilder.ParseContext ctx) {
        String fieldType = field.getFieldType();
        String label = (field.getPartialName() != null
                ? field.getPartialName()
                : "(unnamed)") + " [" + fieldType + "]";
        PdfNode node = new PdfNode("field-" + field.getFullyQualifiedName(),
                label, "field", getFieldIcon(fieldType), "#0dcaf0");
        node.setNodeCategory("field");
        node.addProperty("FieldType", fieldType);
        if (field.getFullyQualifiedName() != null)
            node.addProperty("FullName", field.getFullyQualifiedName());
        if (field.getValueAsString() != null && !field.getValueAsString().isEmpty())
            node.addProperty("Value", field.getValueAsString());
        node.addProperty("ReadOnly", String.valueOf(field.isReadOnly()));
        node.addProperty("Required", String.valueOf(field.isRequired()));
        node.addProperty("FieldSubType", detectFieldSubType(field));

        if (field instanceof PDTextField textField) {
            node.addProperty("Multiline", String.valueOf(textField.isMultiline()));
        }
        if (field instanceof PDComboBox comboBox) {
            node.addProperty("Editable", String.valueOf(comboBox.isEdit()));
        }
        if (field instanceof PDCheckBox) {
            String current = field.getValueAsString();
            boolean checked = current != null && !current.isBlank() && !"Off".equalsIgnoreCase(current);
            node.addProperty("Checked", String.valueOf(checked));
        }
        if (field instanceof PDComboBox || field instanceof PDListBox) {
            String options = extractChoiceOptions(field);
            if (options != null && !options.isBlank()) {
                node.addProperty("Options", options);
            }
        }
        String jsValidation = extractValidationJavaScript(field);
        if (jsValidation != null && !jsValidation.isBlank()) {
            node.addProperty("JavaScript", jsValidation);
        }
        if (!field.getWidgets().isEmpty()) {
            try {
                if (field.getWidgets().get(0).getPage() != null) {
                    int pageIdx = ctx.doc.getPages().indexOf(field.getWidgets().get(0).getPage());
                    if (pageIdx >= 0) {
                        node.setPageIndex(pageIdx);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not resolve page index for field {}", field.getFullyQualifiedName(), e);
            }
            PDRectangle rect = field.getWidgets().get(0).getRectangle();
            if (rect != null) {
                node.setBoundingBox(new double[] {
                        rect.getLowerLeftX(), rect.getLowerLeftY(),
                        rect.getWidth(), rect.getHeight() });
            }
        }
        if (field instanceof PDNonTerminalField) {
            for (PDField child : ((PDNonTerminalField) field).getChildren()) {
                node.addChild(buildFieldNode(child, ctx));
            }
        }
        try {
            cosBuilder.attachCosChildren(node, field.getCOSObject(),
                    "field-" + field.getFullyQualifiedName() + "-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to field {}", field.getFullyQualifiedName(), e);
        }
        return node;
    }

    private String detectFieldSubType(PDField field) {
        if (field instanceof PDTextField)
            return "text";
        if (field instanceof PDCheckBox)
            return "checkbox";
        if (field instanceof PDRadioButton)
            return "radio";
        if (field instanceof PDComboBox)
            return "combo";
        if (field instanceof PDListBox)
            return "list";
        if (field instanceof PDSignatureField)
            return "signature";
        return "unknown";
    }

    private String extractChoiceOptions(PDField field) {
        COSBase optBase = field.getCOSObject().getDictionaryObject(COSName.OPT);
        if (!(optBase instanceof COSArray optArray))
            return null;
        List<String> values = new ArrayList<>();
        for (int i = 0; i < optArray.size(); i++) {
            COSBase item = optArray.getObject(i);
            if (item instanceof COSString cosString) {
                values.add(cosString.getString());
                continue;
            }
            if (item instanceof COSArray pair && pair.size() > 0) {
                COSBase first = pair.getObject(0);
                COSBase second = pair.size() > 1 ? pair.getObject(1) : null;
                if (second instanceof COSString display)
                    values.add(display.getString());
                else if (first instanceof COSString export)
                    values.add(export.getString());
            }
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private String extractValidationJavaScript(PDField field) {
        COSBase aaBase = field.getCOSObject().getDictionaryObject(COSName.AA);
        if (!(aaBase instanceof COSDictionary aaDict))
            return null;
        COSBase validateBase = aaDict.getDictionaryObject(COSName.V);
        if (!(validateBase instanceof COSDictionary validateDict))
            return null;
        String subtype = validateDict.getNameAsString(COSName.S);
        if (!"JavaScript".equals(subtype))
            return null;
        return validateDict.getString(COSName.JS);
    }

    private String getFieldIcon(String fieldType) {
        if (fieldType == null)
            return "fa-question-circle";
        switch (fieldType) {
            case "Tx":
                return "fa-keyboard";
            case "Btn":
                return "fa-check-square";
            case "Ch":
                return "fa-list";
            case "Sig":
                return "fa-signature";
            default:
                return "fa-square";
        }
    }

    // ======================== BOOKMARKS ========================

    private PdfNode buildOutlineTree(PDDocumentOutline outline, CosNodeBuilder.ParseContext ctx) {
        PdfNode bookmarksNode = new PdfNode("bookmarks", "Bookmarks",
                "bookmarks", "fa-bookmark", "#6610f2");
        bookmarksNode.setNodeCategory("bookmarks");
        PDOutlineItem item = outline.getFirstChild();
        int count = 0;
        while (item != null) {
            bookmarksNode.addChild(buildOutlineItemNode(item, count, ctx));
            item = item.getNextSibling();
            count++;
        }
        bookmarksNode.setName("Bookmarks (" + count + ")");
        return bookmarksNode;
    }

    private PdfNode buildOutlineItemNode(PDOutlineItem item, int index,
            CosNodeBuilder.ParseContext ctx) {
        String title = item.getTitle() != null ? item.getTitle() : "(untitled)";
        PdfNode node = new PdfNode("bookmark-" + index + "-" + title.hashCode(),
                title, "bookmark", "fa-bookmark", "#6610f2");
        node.setNodeCategory("bookmark");
        if (item.isBold())
            node.addProperty("Bold", "true");
        if (item.isItalic())
            node.addProperty("Italic", "true");
        try {
            PDPage destPage = item.findDestinationPage(ctx.doc);
            if (destPage != null) {
                int pageIdx = ctx.doc.getPages().indexOf(destPage);
                if (pageIdx >= 0) {
                    node.setPageIndex(pageIdx);
                    node.addProperty("Page", String.valueOf(pageIdx + 1));
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract destination page for bookmark: {}", title, e);
        }
        try {
            cosBuilder.attachCosChildren(node, item.getCOSObject(),
                    "bookmark-" + index + "-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to bookmark", e);
        }
        PDOutlineItem child = item.getFirstChild();
        int childIdx = 0;
        while (child != null) {
            node.addChild(buildOutlineItemNode(child, childIdx, ctx));
            child = child.getNextSibling();
            childIdx++;
        }
        return node;
    }

    // ======================== ATTACHMENTS ========================

    private PdfNode buildAttachmentsNode(PDDocument doc, CosNodeBuilder.ParseContext ctx) {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        PDDocumentNameDictionary names;
        try {
            names = catalog.getNames();
        } catch (Exception e) {
            log.debug("Could not get document names", e);
            return null;
        }
        if (names == null)
            return null;

        PDEmbeddedFilesNameTreeNode embeddedFiles;
        try {
            embeddedFiles = names.getEmbeddedFiles();
        } catch (Exception e) {
            log.debug("Could not get embedded files", e);
            return null;
        }
        if (embeddedFiles == null)
            return null;

        Map<String, PDComplexFileSpecification> fileSpecMap;
        try {
            fileSpecMap = embeddedFiles.getNames();
        } catch (Exception e) {
            log.debug("Could not get embedded file names", e);
            return null;
        }
        if (fileSpecMap == null || fileSpecMap.isEmpty())
            return null;

        PdfNode attachNode = new PdfNode("attachments",
                "Attachments (" + fileSpecMap.size() + ")",
                "attachments", "fa-paperclip", "#fd7e14");
        attachNode.setNodeCategory("attachments");

        int idx = 0;
        for (Map.Entry<String, PDComplexFileSpecification> entry : fileSpecMap.entrySet()) {
            String name = entry.getKey();
            PDComplexFileSpecification spec = entry.getValue();
            PdfNode fileNode = new PdfNode("attachment-" + idx,
                    name, "attachment", "fa-file-alt", "#fd7e14");
            fileNode.setNodeCategory("attachment");

            if (spec.getFileDescription() != null)
                fileNode.addProperty("Description", spec.getFileDescription());

            PDEmbeddedFile ef = spec.getEmbeddedFile();
            if (ef == null)
                ef = spec.getEmbeddedFileUnicode();
            if (ef != null) {
                try {
                    if (ef.getSize() > 0)
                        fileNode.addProperty("Size", ef.getSize() + " bytes");
                    if (ef.getSubtype() != null)
                        fileNode.addProperty("MIME", ef.getSubtype());
                    // Store COSStream reference for download
                    COSBase cosEf = ef.getCOSObject();
                    if (cosEf instanceof COSStream) {
                        COSObjectKey key = null;
                        try {
                            key = ((COSStream) cosEf).getKey();
                        } catch (Exception ignored) {
                        }
                        if (key == null)
                            key = cosBuilder.findObjectKeyInDocument(cosEf, ctx.doc);
                        if (key != null) {
                            fileNode.setObjectNumber((int) key.getNumber());
                            fileNode.setGenerationNumber((int) key.getGeneration());
                            fileNode.setCosType("COSStream");
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error reading embedded file metadata for {}", name, e);
                }
            }
            // Store name for download lookup
            fileNode.addProperty("FileName", name);
            attachNode.addChild(fileNode);
            idx++;
        }
        return attachNode;
    }
}
