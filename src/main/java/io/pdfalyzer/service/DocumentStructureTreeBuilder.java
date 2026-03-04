package io.pdfalyzer.service;

import java.util.Map;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.springframework.stereotype.Component;

import io.pdfalyzer.model.PdfNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds annotation, outline (bookmarks), and attachments subtrees.
 * Used by {@link SemanticTreeBuilder}.
 */
@Component
@Slf4j
public class DocumentStructureTreeBuilder {

    private final CosNodeBuilder cosBuilder;

    public DocumentStructureTreeBuilder(CosNodeBuilder cosBuilder) {
        this.cosBuilder = cosBuilder;
    }

    PdfNode buildAnnotationNode(PDAnnotation annot, int pageIndex,
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

    String getAnnotationIcon(String subtype) {
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

    PdfNode buildOutlineTree(PDDocumentOutline outline, CosNodeBuilder.ParseContext ctx) {
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

    PdfNode buildOutlineItemNode(PDOutlineItem item, int index, CosNodeBuilder.ParseContext ctx) {
        String title = item.getTitle() != null ? item.getTitle() : "(untitled)";
        PdfNode node = new PdfNode("bookmark-" + index + "-" + title.hashCode(),
                title, "bookmark", "fa-bookmark", "#6610f2");
        node.setNodeCategory("bookmark");
        if (item.isBold())
            node.addProperty("Bold", "true");
        if (item.isItalic())
            node.addProperty("Italic", "true");
        try {
            org.apache.pdfbox.pdmodel.PDPage destPage = item.findDestinationPage(ctx.doc);
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

    PdfNode buildAttachmentsNode(PDDocument doc, CosNodeBuilder.ParseContext ctx) {
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
                    if (ef.getSize() > 0)
                        fileNode.addProperty("Size", ef.getSize() + " bytes");
                    if (ef.getSubtype() != null)
                        fileNode.addProperty("MIME", ef.getSubtype());
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
            fileNode.addProperty("FileName", name);
            attachNode.addChild(fileNode);
            idx++;
        }
        return attachNode;
    }
}
