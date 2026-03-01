package io.pdfalyzer.service;

import io.pdfalyzer.model.PdfNode;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, double[]> imageBoundsByKey = extractImageBoundsByObjectKey(page, ctx);
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
                    String imageKey = toObjectKey(imgNode.getObjectNumber(), imgNode.getGenerationNumber());
                    if (imageKey != null) {
                        double[] bounds = imageBoundsByKey.get(imageKey);
                        if (bounds != null) {
                            imgNode.setBoundingBox(bounds);
                        }
                    }
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

    private Map<String, double[]> extractImageBoundsByObjectKey(PDPage page,
                                                                  CosNodeBuilder.ParseContext ctx) {
        Map<String, double[]> boundsByKey = new HashMap<>();
        try {
            new PDFGraphicsStreamEngine(page) {
                @Override
                public void drawImage(PDImage pdImage) throws IOException {
                    int objNum = -1;
                    int genNum = 0;
                    COSBase cosObj = null;

                    if (pdImage instanceof PDImageXObject) {
                        cosObj = ((PDImageXObject) pdImage).getCOSObject();
                    }

                    if (cosObj instanceof COSObject) {
                        objNum = (int) ((COSObject) cosObj).getObjectNumber();
                        genNum = (int) ((COSObject) cosObj).getGenerationNumber();
                    } else if (cosObj instanceof COSStream) {
                        COSObjectKey key = null;
                        try {
                            key = ((COSStream) cosObj).getKey();
                        } catch (Exception ignored) {
                        }
                        if (key == null) {
                            key = cosBuilder.findObjectKeyInDocument(cosObj, ctx.doc);
                        }
                        if (key != null) {
                            objNum = (int) key.getNumber();
                            genNum = (int) key.getGeneration();
                        }
                    }

                    String key = toObjectKey(objNum, genNum);
                    if (key == null) return;

                    Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                    Point2D.Float p0 = ctm.transformPoint(0, 0);
                    Point2D.Float p1 = ctm.transformPoint(1, 0);
                    Point2D.Float p2 = ctm.transformPoint(1, 1);
                    Point2D.Float p3 = ctm.transformPoint(0, 1);

                    double minX = Math.min(Math.min(p0.x, p1.x), Math.min(p2.x, p3.x));
                    double maxX = Math.max(Math.max(p0.x, p1.x), Math.max(p2.x, p3.x));
                    double minY = Math.min(Math.min(p0.y, p1.y), Math.min(p2.y, p3.y));
                    double maxY = Math.max(Math.max(p0.y, p1.y), Math.max(p2.y, p3.y));

                    double[] box = new double[] {
                            minX,
                            minY,
                            Math.max(0, maxX - minX),
                            Math.max(0, maxY - minY)
                    };

                    if (box[2] <= 0 || box[3] <= 0) return;

                    double[] existing = boundsByKey.get(key);
                    if (existing == null) {
                        boundsByKey.put(key, box);
                    } else {
                        double unionMinX = Math.min(existing[0], box[0]);
                        double unionMinY = Math.min(existing[1], box[1]);
                        double unionMaxX = Math.max(existing[0] + existing[2], box[0] + box[2]);
                        double unionMaxY = Math.max(existing[1] + existing[3], box[1] + box[3]);
                        boundsByKey.put(key, new double[] {
                                unionMinX,
                                unionMinY,
                                Math.max(0, unionMaxX - unionMinX),
                                Math.max(0, unionMaxY - unionMinY)
                        });
                    }
                }

                @Override
                public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
                }

                @Override
                public void clip(int windingRule) {
                }

                @Override
                public void moveTo(float x, float y) {
                }

                @Override
                public void lineTo(float x, float y) {
                }

                @Override
                public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
                }

                @Override
                public Point2D getCurrentPoint() {
                    return new Point2D.Float(0, 0);
                }

                @Override
                public void closePath() {
                }

                @Override
                public void endPath() {
                }

                @Override
                public void strokePath() {
                }

                @Override
                public void fillPath(int windingRule) {
                }

                @Override
                public void fillAndStrokePath(int windingRule) {
                }

                @Override
                public void shadingFill(COSName shadingName) {
                }
            }.processPage(page);
        } catch (Exception e) {
            log.debug("Could not extract image bounds for page", e);
        }
        return boundsByKey;
    }

    private String toObjectKey(int objNum, int genNum) {
        if (objNum < 0) return null;
        return objNum + ":" + Math.max(0, genNum);
    }
}
