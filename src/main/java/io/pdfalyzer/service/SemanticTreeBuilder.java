package io.pdfalyzer.service;

import io.pdfalyzer.model.PdfNode;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds the high-level semantic tree for a PDF document.
 * Delegates COS subtree construction to {@link CosNodeBuilder}.
 */
@Component
public class SemanticTreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(SemanticTreeBuilder.class);

    private final CosNodeBuilder cosBuilder;

    public SemanticTreeBuilder(CosNodeBuilder cosBuilder) {
        this.cosBuilder = cosBuilder;
    }

    public PdfNode buildTree(PDDocument doc) {
        CosNodeBuilder.ParseContext ctx = new CosNodeBuilder.ParseContext(doc, 8, false);

        PdfNode root = new PdfNode("catalog", "Document Catalog", "root", "fa-book", "#dc3545");
        root.setNodeCategory("catalog");

        try {
            cosBuilder.attachCosChildren(root, doc.getDocumentCatalog().getCOSObject(),
                    "catalog-cos", ctx, 0);
        } catch (Exception e) { log.warn("Error attaching COS to catalog", e); }

        PDDocumentInformation info = doc.getDocumentInformation();
        if (info != null) root.addChild(buildDocInfoNode(info, ctx));

        root.addChild(buildPagesTree(doc, ctx));

        try {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null) root.addChild(buildAcroFormTree(acroForm, ctx));
        } catch (Exception e) { log.warn("Error parsing AcroForm", e); }

        try {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) root.addChild(buildOutlineTree(outline, ctx));
        } catch (Exception e) { log.warn("Error parsing outlines", e); }

        try {
            PdfNode attachmentsNode = buildAttachmentsNode(doc, ctx);
            if (attachmentsNode != null) root.addChild(attachmentsNode);
        } catch (Exception e) { log.warn("Error parsing attachments", e); }

        return root;
    }

    // ======================== DOC INFO ========================

    private PdfNode buildDocInfoNode(PDDocumentInformation info, CosNodeBuilder.ParseContext ctx) {
        PdfNode node = new PdfNode("docinfo", "Document Info", "info", "fa-info-circle", "#17a2b8");
        node.setNodeCategory("info");
        if (info.getTitle() != null) node.addProperty("Title", info.getTitle());
        if (info.getAuthor() != null) node.addProperty("Author", info.getAuthor());
        if (info.getSubject() != null) node.addProperty("Subject", info.getSubject());
        if (info.getCreator() != null) node.addProperty("Creator", info.getCreator());
        if (info.getProducer() != null) node.addProperty("Producer", info.getProducer());
        if (info.getCreationDate() != null)
            node.addProperty("Created", info.getCreationDate().getTime().toString());
        if (info.getModificationDate() != null)
            node.addProperty("Modified", info.getModificationDate().getTime().toString());
        try {
            cosBuilder.attachCosChildren(node, info.getCOSObject(), "docinfo-cos", ctx, 0);
        } catch (Exception e) { log.debug("Error attaching COS to doc info", e); }
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
                if (fontsFolder != null) resourcesNode.addChild(fontsFolder);
                PdfNode imagesFolder = buildPageImagesNode(page, resources, index, ctx);
                if (imagesFolder != null) resourcesNode.addChild(imagesFolder);
                try {
                    COSDictionary resDict = resources.getCOSObject();
                    if (resDict != null) {
                        cosBuilder.attachCosChildren(resourcesNode, resDict,
                                "page-" + index + "-resources-cos", ctx, 0);
                    }
                } catch (Exception e) {
                    log.debug("Error attaching COS to resources for page {}", index, e);
                }
                if (!resourcesNode.getChildren().isEmpty()) pageNode.addChild(resourcesNode);
            }
        } catch (Exception e) { log.warn("Error parsing resources for page {}", index, e); }

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
        } catch (Exception e) { log.warn("Error parsing annotations for page {}", index, e); }

        try {
            PdfNode csNode = cosBuilder.buildContentStreamNode(page, index, ctx);
            if (!csNode.getChildren().isEmpty()) pageNode.addChild(csNode);
        } catch (Exception e) { log.warn("Error parsing content stream for page {}", index, e); }

        try {
            cosBuilder.attachCosChildren(pageNode, page.getCOSObject(),
                    "page-" + index + "-cos", ctx, 0);
        } catch (Exception e) { log.debug("Error attaching COS to page {}", index, e); }

        return pageNode;
    }

    // ======================== FONTS (per page) ========================

    private PdfNode buildPageFontsNode(PDResources resources, int pageIndex,
                                        CosNodeBuilder.ParseContext ctx) {
        List<PdfNode> fontNodes = new ArrayList<>();
        try {
            for (COSName fontName : resources.getFontNames()) {
                try {
                    PDFont font = resources.getFont(fontName);
                    PdfNode fontNode = new PdfNode(
                            "page-" + pageIndex + "-font-" + fontName.getName(),
                            fontName.getName() + ": " + font.getName(),
                            "font", "fa-font", "#20c997");
                    fontNode.setNodeCategory("font");
                    fontNode.setPageIndex(pageIndex);
                    fontNode.addProperty("Name", font.getName());
                    fontNode.addProperty("Type", font.getClass().getSimpleName().replace("PD", ""));
                    fontNode.addProperty("Embedded", String.valueOf(font.isEmbedded()));
                    String name = font.getName();
                    if (name != null && name.length() > 7 && name.charAt(6) == '+') {
                        fontNode.addProperty("Subset", "true");
                        fontNode.addProperty("Base Name", name.substring(7));
                    }
                    try {
                        cosBuilder.attachCosChildren(fontNode, font.getCOSObject(),
                                "page-" + pageIndex + "-font-" + fontName.getName() + "-cos", ctx, 0);
                    } catch (Exception e) {
                        log.debug("Error attaching COS to font {}", fontName.getName(), e);
                    }
                    fontNodes.add(fontNode);
                } catch (Exception e) {
                    log.debug("Could not parse font {}", fontName.getName(), e);
                }
            }
        } catch (Exception e) { log.debug("Error iterating fonts for page {}", pageIndex, e); }
        if (fontNodes.isEmpty()) return null;
        PdfNode folder = new PdfNode("page-" + pageIndex + "-fonts",
                "Fonts (" + fontNodes.size() + ")", "folder", "fa-font", "#20c997");
        folder.setNodeCategory("fonts");
        folder.setPageIndex(pageIndex);
        fontNodes.forEach(folder::addChild);
        return folder;
    }

    // ======================== IMAGES (per page) ========================

    private PdfNode buildPageImagesNode(PDPage page, PDResources resources,
                                         int pageIndex, CosNodeBuilder.ParseContext ctx) {
        List<PdfNode> imageNodes = new ArrayList<>();
        int pageObjNum = -1;
        int pageGenNum = 0;
        try {
            COSBase pageObj = page.getCOSObject();
            if (pageObj instanceof COSDictionary) {
                COSObjectKey key = null;
                try { key = ((COSDictionary) pageObj).getKey(); } catch (Exception ignored) {}
                if (key == null) key = cosBuilder.findObjectKeyInDocument(pageObj, ctx.doc);
                if (key != null) {
                    pageObjNum = (int) key.getNumber();
                    pageGenNum = (int) key.getGeneration();
                }
            }
        } catch (Exception e) { log.debug("Error extracting page object number", e); }

        try {
            for (COSName xobjName : resources.getXObjectNames()) {
                try {
                    PDXObject xobj = resources.getXObject(xobjName);
                    if (!(xobj instanceof PDImageXObject)) continue;
                    PDImageXObject img = (PDImageXObject) xobj;
                    PdfNode imgNode = new PdfNode(
                            "page-" + pageIndex + "-img-" + xobjName.getName(),
                            xobjName.getName() + " (" + img.getWidth() + "x" + img.getHeight() + ")",
                            "image", "fa-image", "#e83e8c");
                    imgNode.setNodeCategory("image");
                    imgNode.setPageIndex(pageIndex);
                    imgNode.addProperty("Width", String.valueOf(img.getWidth()));
                    imgNode.addProperty("Height", String.valueOf(img.getHeight()));
                    imgNode.addProperty("Color Space", img.getColorSpace().getName());
                    imgNode.addProperty("BitsPerComponent", String.valueOf(img.getBitsPerComponent()));
                    imgNode.setCosType("COSStream");

                    COSBase cosObj = img.getCOSObject();
                    if (cosObj instanceof COSObject) {
                        imgNode.setObjectNumber((int) ((COSObject) cosObj).getObjectNumber());
                        imgNode.setGenerationNumber((int) ((COSObject) cosObj).getGenerationNumber());
                    } else if (cosObj instanceof COSStream) {
                        COSObjectKey key = null;
                        try { key = ((COSStream) cosObj).getKey(); } catch (Exception ignored) {}
                        if (key == null) key = cosBuilder.findObjectKeyInDocument(cosObj, ctx.doc);
                        if (key != null) {
                            imgNode.setObjectNumber((int) key.getNumber());
                            imgNode.setGenerationNumber((int) key.getGeneration());
                        }
                    }
                    if (imgNode.getObjectNumber() < 0 && pageObjNum >= 0) {
                        imgNode.setObjectNumber(pageObjNum);
                        imgNode.setGenerationNumber(pageGenNum);
                    }
                    if (pageObjNum >= 0) {
                        List<String> kp = new ArrayList<>();
                        kp.add("Resources");
                        kp.add("XObject");
                        kp.add("/" + xobjName.getName());
                        imgNode.setKeyPath(cosBuilder.keyPathToJson(kp));
                    }
                    try {
                        cosBuilder.attachCosChildren(imgNode, img.getCOSObject(),
                                "page-" + pageIndex + "-img-" + xobjName.getName() + "-cos", ctx, 0);
                    } catch (Exception e) {
                        log.debug("Error attaching COS to image {}", xobjName.getName(), e);
                    }
                    imageNodes.add(imgNode);
                } catch (Exception e) {
                    log.debug("Could not parse XObject {}", xobjName.getName(), e);
                }
            }
        } catch (Exception e) { log.debug("Error iterating XObjects for page {}", pageIndex, e); }

        if (imageNodes.isEmpty()) return null;
        PdfNode folder = new PdfNode("page-" + pageIndex + "-images",
                "Images (" + imageNodes.size() + ")", "folder", "fa-images", "#e83e8c");
        folder.setNodeCategory("images");
        folder.setPageIndex(pageIndex);
        imageNodes.forEach(folder::addChild);
        return folder;
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
        if (subtype != null) node.addProperty("Subtype", subtype);
        if (annot.getContents() != null) node.addProperty("Contents", annot.getContents());
        if (annot.getAnnotationName() != null) node.addProperty("Name", annot.getAnnotationName());
        PDRectangle rect = annot.getRectangle();
        if (rect != null) {
            node.setBoundingBox(new double[]{
                    rect.getLowerLeftX(), rect.getLowerLeftY(),
                    rect.getWidth(), rect.getHeight()});
            node.addProperty("Rectangle", String.format("%.1f, %.1f, %.1f, %.1f",
                    rect.getLowerLeftX(), rect.getLowerLeftY(),
                    rect.getUpperRightX(), rect.getUpperRightY()));
        }
        try {
            cosBuilder.attachCosChildren(node, annot.getCOSObject(),
                    "page-" + pageIndex + "-annot-" + annotIndex + "-cos", ctx, 0);
        } catch (Exception e) { log.debug("Error attaching COS to annotation", e); }
        return node;
    }

    private String getAnnotationIcon(String subtype) {
        if (subtype == null) return "fa-question-circle";
        switch (subtype) {
            case "Link": return "fa-link";
            case "Text": return "fa-comment";
            case "Widget": return "fa-cog";
            case "Highlight": return "fa-highlighter";
            case "Underline": return "fa-underline";
            case "StrikeOut": return "fa-strikethrough";
            case "Stamp": return "fa-stamp";
            case "FreeText": return "fa-pen";
            default: return "fa-sticky-note";
        }
    }

    // ======================== ACROFORM ========================

    private PdfNode buildAcroFormTree(PDAcroForm acroForm, CosNodeBuilder.ParseContext ctx) {
        List<PDField> fields = acroForm.getFields();
        PdfNode formNode = new PdfNode("acroform",
                "AcroForm (" + fields.size() + " fields)", "acroform", "fa-wpforms", "#0dcaf0");
        formNode.setNodeCategory("acroform");
        if (acroForm.isSignaturesExist()) formNode.addProperty("SignaturesExist", "true");
        formNode.addProperty("NeedAppearances", String.valueOf(acroForm.getNeedAppearances()));
        for (PDField field : fields) {
            formNode.addChild(buildFieldNode(field, ctx));
        }
        try {
            cosBuilder.attachCosChildren(formNode, acroForm.getCOSObject(), "acroform-cos", ctx, 0);
        } catch (Exception e) { log.debug("Error attaching COS to acroform", e); }
        return formNode;
    }

    private PdfNode buildFieldNode(PDField field, CosNodeBuilder.ParseContext ctx) {
        String fieldType = field.getFieldType();
        String label = (field.getPartialName() != null
                ? field.getPartialName() : "(unnamed)") + " [" + fieldType + "]";
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
        if (!field.getWidgets().isEmpty()) {
            PDRectangle rect = field.getWidgets().get(0).getRectangle();
            if (rect != null) {
                node.setBoundingBox(new double[]{
                        rect.getLowerLeftX(), rect.getLowerLeftY(),
                        rect.getWidth(), rect.getHeight()});
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

    private String getFieldIcon(String fieldType) {
        if (fieldType == null) return "fa-question-circle";
        switch (fieldType) {
            case "Tx": return "fa-keyboard";
            case "Btn": return "fa-check-square";
            case "Ch": return "fa-list";
            case "Sig": return "fa-signature";
            default: return "fa-square";
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
        if (item.isBold()) node.addProperty("Bold", "true");
        if (item.isItalic()) node.addProperty("Italic", "true");
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
        } catch (Exception e) { log.debug("Error attaching COS to bookmark", e); }
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
        if (names == null) return null;

        PDEmbeddedFilesNameTreeNode embeddedFiles;
        try {
            embeddedFiles = names.getEmbeddedFiles();
        } catch (Exception e) {
            log.debug("Could not get embedded files", e);
            return null;
        }
        if (embeddedFiles == null) return null;

        Map<String, PDComplexFileSpecification> fileSpecMap;
        try {
            fileSpecMap = embeddedFiles.getNames();
        } catch (Exception e) {
            log.debug("Could not get embedded file names", e);
            return null;
        }
        if (fileSpecMap == null || fileSpecMap.isEmpty()) return null;

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
            if (ef == null) ef = spec.getEmbeddedFileUnicode();
            if (ef != null) {
                try {
                    if (ef.getSize() > 0) fileNode.addProperty("Size", ef.getSize() + " bytes");
                    if (ef.getSubtype() != null)
                        fileNode.addProperty("MIME", ef.getSubtype());
                    // Store COSStream reference for download
                    COSBase cosEf = ef.getCOSObject();
                    if (cosEf instanceof COSStream) {
                        COSObjectKey key = null;
                        try { key = ((COSStream) cosEf).getKey(); } catch (Exception ignored) {}
                        if (key == null) key = cosBuilder.findObjectKeyInDocument(cosEf, ctx.doc);
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
