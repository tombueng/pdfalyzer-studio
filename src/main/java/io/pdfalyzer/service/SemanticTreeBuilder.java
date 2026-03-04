package io.pdfalyzer.service;

import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.springframework.stereotype.Component;

import io.pdfalyzer.model.PdfNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the high-level semantic tree for a PDF document.
 * Delegates COS subtree construction to {@link CosNodeBuilder},
 * per-page resource (fonts/images) construction to {@link PageResourceBuilder},
 * AcroForm building to {@link AcroFormTreeBuilder}, and document structure
 * (annotations, outlines, attachments) to {@link DocumentStructureTreeBuilder}.
 */
@Component
@Slf4j
public class SemanticTreeBuilder {

    private final CosNodeBuilder cosBuilder;
    private final PageResourceBuilder pageResourceBuilder;
    private final AcroFormTreeBuilder acroFormTreeBuilder;
    private final DocumentStructureTreeBuilder structureTreeBuilder;

    public SemanticTreeBuilder(CosNodeBuilder cosBuilder,
                               PageResourceBuilder pageResourceBuilder,
                               AcroFormTreeBuilder acroFormTreeBuilder,
                               DocumentStructureTreeBuilder structureTreeBuilder) {
        this.cosBuilder = cosBuilder;
        this.pageResourceBuilder = pageResourceBuilder;
        this.acroFormTreeBuilder = acroFormTreeBuilder;
        this.structureTreeBuilder = structureTreeBuilder;
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
                root.addChild(acroFormTreeBuilder.buildAcroFormTree(acroForm, ctx));
        } catch (Exception e) {
            log.warn("Error parsing AcroForm", e);
        }

        try {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null)
                root.addChild(structureTreeBuilder.buildOutlineTree(outline, ctx));
        } catch (Exception e) {
            log.warn("Error parsing outlines", e);
        }

        try {
            PdfNode attachmentsNode = structureTreeBuilder.buildAttachmentsNode(doc, ctx);
            if (attachmentsNode != null)
                root.addChild(attachmentsNode);
        } catch (Exception e) {
            log.warn("Error parsing attachments", e);
        }

        return root;
    }

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
        } catch (Exception e) {
            log.debug("Error attaching COS to doc info", e);
        }
        return node;
    }

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
                PdfNode fontsFolder = pageResourceBuilder.buildPageFontsNode(resources, index, ctx);
                if (fontsFolder != null) resourcesNode.addChild(fontsFolder);
                PdfNode imagesFolder = pageResourceBuilder.buildPageImagesNode(page, resources, index, ctx);
                if (imagesFolder != null) resourcesNode.addChild(imagesFolder);
                try {
                    org.apache.pdfbox.cos.COSDictionary resDict = resources.getCOSObject();
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
                    annotsNode.addChild(structureTreeBuilder.buildAnnotationNode(annotations.get(a), index, a, ctx));
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
}
