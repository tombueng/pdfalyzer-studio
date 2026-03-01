package io.pdfalyzer.service;

import io.pdfalyzer.model.PdfNode;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Fonts and Images sub-nodes for a single page's Resources node.
 * Extracted from {@link SemanticTreeBuilder} to keep file sizes under ~500 lines.
 */
@Component
public class PageResourceBuilder {

    private static final Logger log = LoggerFactory.getLogger(PageResourceBuilder.class);

    private final CosNodeBuilder cosBuilder;

    public PageResourceBuilder(CosNodeBuilder cosBuilder) {
        this.cosBuilder = cosBuilder;
    }

    /** Build a "Fonts (N)" folder node for a page, or null if no fonts. */
    public PdfNode buildPageFontsNode(PDResources resources, int pageIndex,
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
        } catch (Exception e) {
            log.debug("Error iterating fonts for page {}", pageIndex, e);
        }
        if (fontNodes.isEmpty()) return null;
        PdfNode folder = new PdfNode("page-" + pageIndex + "-fonts",
                "Fonts (" + fontNodes.size() + ")", "folder", "fa-font", "#20c997");
        folder.setNodeCategory("fonts");
        folder.setPageIndex(pageIndex);
        fontNodes.forEach(folder::addChild);
        return folder;
    }

    /** Build an "Images (N)" folder node for a page, or null if no images. */
    public PdfNode buildPageImagesNode(PDPage page, PDResources resources,
                                        int pageIndex, CosNodeBuilder.ParseContext ctx) {
        int pageObjNum = -1;
        int pageGenNum = 0;
        try {
            COSBase pageObj = page.getCOSObject();
            if (pageObj instanceof COSDictionary) {
                COSObjectKey key = null;
                try { key = ((COSDictionary) pageObj).getKey(); } catch (Exception ignored) {}
                if (key == null) key = cosBuilder.findObjectKeyInDocument(pageObj, ctx.doc);
                if (key != null) { pageObjNum = (int) key.getNumber(); pageGenNum = (int) key.getGeneration(); }
            }
        } catch (Exception e) {
            log.debug("Error extracting page object number", e);
        }

        List<PdfNode> imageNodes = new ArrayList<>();
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
                    resolveImageObjectNumber(img, imgNode, pageObjNum, pageGenNum, ctx);
                    if (pageObjNum >= 0) {
                        List<String> kp = new ArrayList<>();
                        kp.add("Resources"); kp.add("XObject");
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
        } catch (Exception e) {
            log.debug("Error iterating XObjects for page {}", pageIndex, e);
        }
        if (imageNodes.isEmpty()) return null;
        PdfNode folder = new PdfNode("page-" + pageIndex + "-images",
                "Images (" + imageNodes.size() + ")", "folder", "fa-images", "#e83e8c");
        folder.setNodeCategory("images");
        folder.setPageIndex(pageIndex);
        imageNodes.forEach(folder::addChild);
        return folder;
    }

    private void resolveImageObjectNumber(PDImageXObject img, PdfNode imgNode,
                                           int fallbackObjNum, int fallbackGenNum,
                                           CosNodeBuilder.ParseContext ctx) {
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
        if (imgNode.getObjectNumber() < 0 && fallbackObjNum >= 0) {
            imgNode.setObjectNumber(fallbackObjNum);
            imgNode.setGenerationNumber(fallbackGenNum);
        }
    }
}
