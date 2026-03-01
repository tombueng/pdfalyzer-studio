package io.pdfalyzer.service;

import io.pdfalyzer.model.PdfNode;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class PdfStructureParser {

    private static final Logger log = LoggerFactory.getLogger(PdfStructureParser.class);

    // Depth limit for COS traversal under semantic nodes (keep fast)
    private static final int COS_DEPTH_SEMANTIC = 8;
    // Depth limit for raw COS view (full exploration)
    private static final int COS_DEPTH_RAW = 50;
    private static final int MAX_STREAM_PREVIEW = 512;

    /**
     * Per-parse context: shared visited set and node cache across the entire tree build.
     * This prevents the same indirect object from being fully traversed multiple times.
     */
    private static class ParseContext {
        final Set<COSObjectKey> visited = new HashSet<>();
        final Map<COSObjectKey, PdfNode> cache = new HashMap<>();
        final int maxCosDepth;
        final boolean readStreamData;
        final PDDocument doc;

        ParseContext(PDDocument doc, int maxCosDepth, boolean readStreamData) {
            this.doc = doc;
            this.maxCosDepth = maxCosDepth;
            this.readStreamData = readStreamData;
        }
    }

    // ======================== SEMANTIC TREE ========================

    public PdfNode buildTree(PDDocument doc) {
        // Shared context: low depth, no stream data reading, shared cache
        ParseContext ctx = new ParseContext(doc, COS_DEPTH_SEMANTIC, false);

        PdfNode root = new PdfNode("catalog", "Document Catalog", "root", "fa-book", "#dc3545");
        root.setNodeCategory("catalog");

        // Attach raw COS of the catalog
        try {
            COSDictionary catalogDict = doc.getDocumentCatalog().getCOSObject();
            attachCosChildren(root, catalogDict, "catalog-cos", ctx, 0);
        } catch (Exception e) {
            log.warn("Error attaching COS to catalog", e);
        }

        // Document info
        PDDocumentInformation info = doc.getDocumentInformation();
        if (info != null) {
            root.addChild(buildDocInfoNode(info, ctx));
        }

        // Pages
        root.addChild(buildPagesTree(doc, ctx));

        // AcroForm
        try {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null) {
                root.addChild(buildAcroFormTree(acroForm, ctx));
            }
        } catch (Exception e) {
            log.warn("Error parsing AcroForm", e);
        }

        // Bookmarks / Outlines
        try {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                root.addChild(buildOutlineTree(outline, ctx));
            }
        } catch (Exception e) {
            log.warn("Error parsing outlines", e);
        }

        log.debug("Built semantic tree with {} cached COS objects", ctx.cache.size());
        return root;
    }

    // ======================== RAW COS TREE (on-demand) ========================

    public PdfNode buildRawCosTree(PDDocument doc) {
        // Fresh context: high depth, with stream data, fresh cache
        ParseContext ctx = new ParseContext(doc, COS_DEPTH_RAW, true);

        PdfNode root = new PdfNode("raw-cos", "All COS Objects", "root", "fa-database", "#e74c3c");
        root.setNodeCategory("raw-cos");

        COSDocument cosDoc = doc.getDocument();
        Map<COSObjectKey, Long> xrefTable = cosDoc.getXrefTable();

        List<COSObjectKey> sortedKeys = new ArrayList<>(xrefTable.keySet());
        sortedKeys.sort(Comparator.comparingLong(COSObjectKey::getNumber));

        for (COSObjectKey key : sortedKeys) {
            try {
                COSObject obj = cosDoc.getObjectFromPool(key);
                if (obj == null || obj.getObject() == null) continue;

                String label = "obj " + key.getNumber() + " " + key.getGeneration();
                String idPrefix = "cos-" + key.getNumber() + "-" + key.getGeneration();

                // Each top-level xref object gets its own visited set to allow
                // full exploration, but still detect cycles within each object graph
                Set<COSObjectKey> localVisited = new HashSet<>();
                PdfNode node = buildCosNode(obj.getObject(), label, idPrefix, localVisited, 0, new ArrayList<>(), ctx);
                node.setObjectNumber((int) key.getNumber());
                node.setGenerationNumber(key.getGeneration());
                propagateObjectInfo(node, (int) key.getNumber(), key.getGeneration());
                root.addChild(node);
            } catch (Exception e) {
                log.debug("Error reading COS object {}", key, e);
            }
        }

        root.setName("All COS Objects (" + root.getChildren().size() + ")");
        return root;
    }

    // ======================== COS NODE BUILDER ========================

    private PdfNode buildCosNode(COSBase obj, String label, String idPrefix,
                                  Set<COSObjectKey> visited, int depth,
                                  List<String> currentKeyPath, ParseContext ctx) {
        if (depth > ctx.maxCosDepth) {
            return makeLeafNode(idPrefix, label + " (max depth)", "COSNull",
                    "(depth limit reached)", "fa-ban", "#666");
        }
        if (obj == null) {
            return makeLeafNode(idPrefix, label + ": null", "COSNull",
                    "null", "fa-circle", "#888");
        }

        // Unwrap COSObject (indirect reference)
        if (obj instanceof COSObject) {
            COSObject cosObj = (COSObject) obj;
            COSObjectKey key = cosObj.getKey();
            if (key != null) {
                String objLabel = label + " (obj " + key.getNumber() + " " + key.getGeneration() + " R)";

                if (visited.contains(key)) {
                    PdfNode ref = makeLeafNode(idPrefix, objLabel + " [circular ref]",
                            "COSObject", "-> obj " + key.getNumber(),
                            "fa-link", "#ffffff");
                    ref.setObjectNumber((int) key.getNumber());
                    ref.setGenerationNumber(key.getGeneration());
                    // indicate which object is being referenced so UI can jump there
                    ref.addProperty("refTarget", String.valueOf(key.getNumber()));
                    // even an empty list is serialized to []
                    ref.setKeyPath(keyPathToJson(currentKeyPath));
                    return ref;
                }

                // Check cache — return a reference node if already fully traversed
                PdfNode cached = ctx.cache.get(key);
                if (cached != null) {
                    PdfNode ref = makeLeafNode(idPrefix,
                            objLabel + " [see obj " + key.getNumber() + "]",
                            "COSObject", "-> obj " + key.getNumber(),
                            "fa-link", "#ffffff");
                    ref.setObjectNumber((int) key.getNumber());
                    ref.setGenerationNumber(key.getGeneration());
                    return ref;
                }

                visited.add(key);

                PdfNode node = buildCosNode(cosObj.getObject(), objLabel, idPrefix,
                        visited, depth + 1, currentKeyPath, ctx);
                node.setObjectNumber((int) key.getNumber());
                node.setGenerationNumber(key.getGeneration());
                node.setKeyPath(keyPathToJson(currentKeyPath));

                // Cache the result
                ctx.cache.put(key, node);
                return node;
            } else {
                return buildCosNode(cosObj.getObject(), label, idPrefix,
                        visited, depth + 1, currentKeyPath, ctx);
            }
        }

        // Primitive types
        if (obj instanceof COSNull) {
            return makePrimitiveNode(idPrefix, label + ": null", "COSNull", "null",
                    false, "null", "fa-circle", "#888888", currentKeyPath);
        }
        if (obj instanceof COSBoolean) {
            String val = String.valueOf(((COSBoolean) obj).getValue());
            return makePrimitiveNode(idPrefix, label + ": " + val, "COSBoolean", val,
                    true, "boolean", "fa-toggle-on", "#9b59b6", currentKeyPath);
        }
        if (obj instanceof COSInteger) {
            String val = String.valueOf(((COSInteger) obj).longValue());
            return makePrimitiveNode(idPrefix, label + ": " + val, "COSInteger", val,
                    true, "integer", "fa-hashtag", "#3498db", currentKeyPath);
        }
        if (obj instanceof COSFloat) {
            String val = String.valueOf(((COSFloat) obj).floatValue());
            return makePrimitiveNode(idPrefix, label + ": " + val, "COSFloat", val,
                    true, "float", "fa-percentage", "#1abc9c", currentKeyPath);
        }
        if (obj instanceof COSString) {
            COSString cosStr = (COSString) obj;
            String val = cosStr.getString();
            boolean isBinary = containsBinaryData(cosStr.getBytes());
            String displayVal = isBinary ? cosStr.toHexString() : val;
            String vType = isBinary ? "hex-string" : "string";
            return makePrimitiveNode(idPrefix, label + ": \"" + truncate(displayVal, 60) + "\"",
                    "COSString", displayVal, true, vType,
                    "fa-quote-right", "#2ecc71", currentKeyPath);
        }
        if (obj instanceof COSName) {
            String val = ((COSName) obj).getName();
            return makePrimitiveNode(idPrefix, label + ": /" + val, "COSName", val,
                    true, "name", "fa-slash", "#e67e22", currentKeyPath);
        }

        // Container: COSArray
        if (obj instanceof COSArray) {
            COSArray arr = (COSArray) obj;
            PdfNode node = new PdfNode(idPrefix, label + " [" + arr.size() + " items]",
                    "COSArray", "fa-list", "#f1c40f");
            node.setCosType("COSArray");
            node.setNodeCategory("cos");
            node.setKeyPath(keyPathToJson(currentKeyPath));
            for (int i = 0; i < arr.size(); i++) {
                List<String> childPath = new ArrayList<>(currentKeyPath);
                childPath.add(String.valueOf(i));
                node.addChild(buildCosNode(arr.get(i), "[" + i + "]",
                        idPrefix + "-" + i, visited, depth + 1, childPath, ctx));
            }
            return node;
        }

        // Container: COSStream (extends COSDictionary, check first)
        if (obj instanceof COSStream) {
            COSStream stream = (COSStream) obj;
            PdfNode node = new PdfNode(idPrefix, label + " <stream>",
                    "COSStream", "fa-water", "#e74c3c");
            node.setCosType("COSStream");
            node.setNodeCategory("cos");
            node.setKeyPath(keyPathToJson(currentKeyPath));

            for (Map.Entry<COSName, COSBase> entry : stream.entrySet()) {
                String key = entry.getKey().getName();
                List<String> childPath = new ArrayList<>(currentKeyPath);
                childPath.add(key);
                node.addChild(buildCosNode(entry.getValue(), "/" + key,
                        idPrefix + "-" + key, visited, depth + 1, childPath, ctx));
            }

            // Stream data preview — only if enabled (raw COS view, not semantic tree)
            if (ctx.readStreamData) {
                try {
                    PdfNode dataNode = new PdfNode(idPrefix + "-data",
                            "Stream Data", "COSStream", "fa-database", "#e74c3c");
                    dataNode.setCosType("COSStream");
                    dataNode.setNodeCategory("cos-stream-data");
                    byte[] data;
                    try (java.io.InputStream is = stream.createInputStream()) {
                        data = is.readAllBytes();
                    }
                    dataNode.addProperty("Length", String.valueOf(data.length));
                    int previewLen = Math.min(data.length, MAX_STREAM_PREVIEW);
                    dataNode.addProperty("Preview (hex)", bytesToHex(data, previewLen));
                    if (isTextContent(data)) {
                        dataNode.addProperty("Preview (text)",
                                new String(data, 0, previewLen, StandardCharsets.ISO_8859_1));
                    }
                    node.addChild(dataNode);
                } catch (Exception e) {
                    log.debug("Could not read stream data for {}", idPrefix, e);
                }
            }

            return node;
        }

        // Container: COSDictionary
        if (obj instanceof COSDictionary) {
            COSDictionary dict = (COSDictionary) obj;
            String typeName = dict.getNameAsString(COSName.TYPE);
            String subtypeName = dict.getNameAsString(COSName.SUBTYPE);
            String extra = "";
            if (typeName != null) extra += " /Type=" + typeName;
            if (subtypeName != null) extra += " /Subtype=" + subtypeName;

            PdfNode node = new PdfNode(idPrefix, label + " {" + dict.size() + " entries}" + extra,
                    "COSDictionary", "fa-th-list", "#00bcd4");
            node.setCosType("COSDictionary");
            node.setNodeCategory("cos");
            node.setKeyPath(keyPathToJson(currentKeyPath));

            for (Map.Entry<COSName, COSBase> entry : dict.entrySet()) {
                String key = entry.getKey().getName();
                List<String> childPath = new ArrayList<>(currentKeyPath);
                childPath.add(key);
                node.addChild(buildCosNode(entry.getValue(), "/" + key,
                        idPrefix + "-" + key, visited, depth + 1, childPath, ctx));
            }
            return node;
        }

        // Fallback
        return makeLeafNode(idPrefix, label + ": " + obj.toString(),
                "unknown", obj.toString(), "fa-question", "#999");
    }

    // ======================== CONTENT STREAM PARSER ========================

    private PdfNode buildContentStreamNode(PDPage page, int pageIndex, ParseContext ctx) {
        PdfNode csNode = new PdfNode("page-" + pageIndex + "-cs",
                "Content Stream", "content-stream", "fa-code", "#ff6b6b");
        csNode.setNodeCategory("content-stream");
        csNode.setPageIndex(pageIndex);

        try {
            PDFStreamParser parser = new PDFStreamParser(page);
            List<Object> tokens = parser.parse();

            List<COSBase> operands = new ArrayList<>();
            int opIndex = 0;

            for (Object token : tokens) {
                if (token instanceof Operator) {
                    Operator op = (Operator) token;
                    String opName = op.getName();
                    PdfNode opNode = new PdfNode(
                            "page-" + pageIndex + "-op-" + opIndex,
                            opName + " (" + getOperatorDescription(opName) + ")",
                            "Operator", "fa-cog", "#ff69b4");
                    opNode.setCosType("Operator");
                    opNode.setNodeCategory("cos");
                    opNode.setPageIndex(pageIndex);
                    opNode.setRawValue(opName);

                    for (int i = 0; i < operands.size(); i++) {
                        opNode.addChild(buildCosNode(operands.get(i),
                                "operand[" + i + "]",
                                "page-" + pageIndex + "-op-" + opIndex + "-arg-" + i,
                                new HashSet<>(), 0, new ArrayList<>(), ctx));
                    }
                    operands.clear();
                    csNode.addChild(opNode);
                    opIndex++;
                } else if (token instanceof COSBase) {
                    operands.add((COSBase) token);
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing content stream for page {}", pageIndex, e);
        }

        csNode.setName("Content Stream (" + csNode.getChildren().size() + " operators)");
        return csNode;
    }

    // ======================== HELPER: ATTACH COS TO SEMANTIC NODE ========================

    private void attachCosChildren(PdfNode parent, COSDictionary dict,
                                    String idPrefix, ParseContext ctx, int depth) {
        PdfNode cosNode = buildCosNode(dict, "COS Dictionary", idPrefix, ctx.visited, depth, new ArrayList<>(), ctx);
        cosNode.setName("Raw COS Data {" + dict.size() + " entries}");
        cosNode.setIcon("fa-th-list");
        cosNode.setColor("#00bcd4");
        parent.addChild(cosNode);
    }

    // ======================== HELPER: PRIMITIVE NODE FACTORIES ========================

    private PdfNode makePrimitiveNode(String id, String name, String cosType, String rawValue,
                                       boolean editable, String valueType, String icon,
                                       String color, List<String> keyPath) {
        PdfNode node = new PdfNode(id, name, cosType, icon, color);
        node.setCosType(cosType);
        node.setRawValue(rawValue);
        node.setEditable(editable);
        node.setValueType(valueType);
        node.setNodeCategory("cos");
        if (keyPath != null && !keyPath.isEmpty()) {
            node.setKeyPath(keyPathToJson(keyPath));
        }
        return node;
    }

    private PdfNode makeLeafNode(String id, String name, String cosType,
                                  String rawValue, String icon, String color) {
        PdfNode node = new PdfNode(id, name, cosType, icon, color);
        node.setCosType(cosType);
        node.setRawValue(rawValue);
        node.setNodeCategory("cos");
        return node;
    }

    // ======================== HELPER: OBJECT NUMBER PROPAGATION ========================

    private void propagateObjectInfo(PdfNode node, int objNum, int genNum) {
        if (node.getObjectNumber() < 0) {
            node.setObjectNumber(objNum);
            node.setGenerationNumber(genNum);
        }
        for (PdfNode child : node.getChildren()) {
            propagateObjectInfo(child, objNum, genNum);
        }
    }

    // ======================== HELPER: UTILITIES ========================

    private boolean containsBinaryData(byte[] data) {
        for (byte b : data) {
            if (b < 0x09 || (b > 0x0D && b < 0x20)) return true;
        }
        return false;
    }

    private String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
            if ((i + 1) % 32 == 0) sb.append("\n");
        }
        if (length < bytes.length) sb.append("...");
        return sb.toString();
    }

    private boolean isTextContent(byte[] data) {
        int textChars = 0;
        int limit = Math.min(data.length, 256);
        for (int i = 0; i < limit; i++) {
            if (data[i] >= 0x20 && data[i] < 0x7F) textChars++;
        }
        return limit > 0 && (textChars * 100 / limit) > 70;
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String keyPathToJson(List<String> keyPath) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keyPath.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(keyPath.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String getOperatorDescription(String op) {
        switch (op) {
            case "BT": return "Begin Text";
            case "ET": return "End Text";
            case "Tf": return "Set Font";
            case "Td": return "Move Text Position";
            case "TD": return "Move Text Position (set leading)";
            case "Tm": return "Set Text Matrix";
            case "T*": return "Move to Next Line";
            case "TJ": return "Show Text Array";
            case "Tj": return "Show Text";
            case "Tc": return "Set Char Spacing";
            case "Tw": return "Set Word Spacing";
            case "Tz": return "Set Horizontal Scale";
            case "TL": return "Set Text Leading";
            case "Tr": return "Set Text Rendering Mode";
            case "Ts": return "Set Text Rise";
            case "cm": return "Set Matrix";
            case "q": return "Save Graphics State";
            case "Q": return "Restore Graphics State";
            case "re": return "Rectangle";
            case "m": return "Move To";
            case "l": return "Line To";
            case "c": return "Curve To (cubic)";
            case "v": return "Curve To (initial)";
            case "y": return "Curve To (final)";
            case "h": return "Close Subpath";
            case "f": case "F": return "Fill (nonzero)";
            case "f*": return "Fill (even-odd)";
            case "S": return "Stroke";
            case "s": return "Close and Stroke";
            case "B": return "Fill and Stroke";
            case "B*": return "Fill and Stroke (even-odd)";
            case "b": return "Close, Fill and Stroke";
            case "b*": return "Close, Fill and Stroke (even-odd)";
            case "n": return "End Path (no-op)";
            case "W": return "Clip (nonzero)";
            case "W*": return "Clip (even-odd)";
            case "Do": return "Invoke XObject";
            case "gs": return "Set Graphics State";
            case "CS": return "Set Color Space (stroke)";
            case "cs": return "Set Color Space (fill)";
            case "SC": return "Set Color (stroke)";
            case "sc": return "Set Color (fill)";
            case "SCN": return "Set Color (stroke, extended)";
            case "scn": return "Set Color (fill, extended)";
            case "G": return "Set Gray (stroke)";
            case "g": return "Set Gray (fill)";
            case "RG": return "Set RGB (stroke)";
            case "rg": return "Set RGB (fill)";
            case "K": return "Set CMYK (stroke)";
            case "k": return "Set CMYK (fill)";
            case "w": return "Set Line Width";
            case "J": return "Set Line Cap";
            case "j": return "Set Line Join";
            case "M": return "Set Miter Limit";
            case "d": return "Set Dash Pattern";
            case "ri": return "Set Rendering Intent";
            case "i": return "Set Flatness";
            case "BI": return "Begin Inline Image";
            case "ID": return "Inline Image Data";
            case "EI": return "End Inline Image";
            case "BMC": return "Begin Marked Content";
            case "BDC": return "Begin Marked Content (props)";
            case "EMC": return "End Marked Content";
            case "MP": return "Marked Content Point";
            case "DP": return "Marked Content Point (props)";
            case "BX": return "Begin Compatibility";
            case "EX": return "End Compatibility";
            case "'": return "Move to Next Line and Show Text";
            case "\"": return "Set Spacing, Move and Show Text";
            case "d0": return "Set Glyph Width (Type3)";
            case "d1": return "Set Glyph Width and BBox (Type3)";
            case "sh": return "Paint Shading";
            default: return op;
        }
    }

    // ======================== SEMANTIC: DOC INFO ========================

    private PdfNode buildDocInfoNode(PDDocumentInformation info, ParseContext ctx) {
        PdfNode node = new PdfNode("docinfo", "Document Info", "info", "fa-info-circle", "#17a2b8");
        node.setNodeCategory("info");
        if (info.getTitle() != null) node.addProperty("Title", info.getTitle());
        if (info.getAuthor() != null) node.addProperty("Author", info.getAuthor());
        if (info.getSubject() != null) node.addProperty("Subject", info.getSubject());
        if (info.getCreator() != null) node.addProperty("Creator", info.getCreator());
        if (info.getProducer() != null) node.addProperty("Producer", info.getProducer());
        if (info.getCreationDate() != null) node.addProperty("Created", info.getCreationDate().getTime().toString());
        if (info.getModificationDate() != null) node.addProperty("Modified", info.getModificationDate().getTime().toString());

        try {
            attachCosChildren(node, info.getCOSObject(), "docinfo-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to doc info", e);
        }

        return node;
    }

    // ======================== SEMANTIC: PAGES ========================

    private PdfNode buildPagesTree(PDDocument doc, ParseContext ctx) {
        PdfNode pagesNode = new PdfNode("pages", "Pages (" + doc.getNumberOfPages() + ")", "folder", "fa-copy", "#ffc107");
        pagesNode.setNodeCategory("pages");

        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            try {
                PDPage page = doc.getPage(i);
                pagesNode.addChild(buildPageNode(page, i, ctx));
            } catch (Exception e) {
                log.warn("Error parsing page {}", i, e);
                PdfNode errorNode = new PdfNode("page-" + i, "Page " + (i + 1) + " (error)", "page", "fa-exclamation-triangle", "#dc3545");
                pagesNode.addChild(errorNode);
            }
        }
        return pagesNode;
    }

    private PdfNode buildPageNode(PDPage page, int index, ParseContext ctx) {
        PDRectangle mediaBox = page.getMediaBox();
        String dims = String.format("%.0f x %.0f", mediaBox.getWidth(), mediaBox.getHeight());
        PdfNode pageNode = new PdfNode("page-" + index, "Page " + (index + 1), "page", "fa-file-alt", "#6c757d");
        pageNode.setNodeCategory("page");
        pageNode.setPageIndex(index);
        pageNode.addProperty("MediaBox", dims);
        pageNode.addProperty("Rotation", String.valueOf(page.getRotation()));

        if (page.getCropBox() != null && !page.getCropBox().equals(mediaBox)) {
            pageNode.addProperty("CropBox", String.format("%.0f x %.0f", page.getCropBox().getWidth(), page.getCropBox().getHeight()));
        }

        // Resources
        try {
            PDResources resources = page.getResources();
            if (resources != null) {
                PdfNode resourcesNode = new PdfNode("page-" + index + "-resources", "Resources", "folder", "fa-cubes", "#6f42c1");
                resourcesNode.setNodeCategory("resources");
                resourcesNode.setPageIndex(index);

                PdfNode fontsFolder = buildPageFontsNode(resources, index, ctx);
                if (fontsFolder != null) resourcesNode.addChild(fontsFolder);

                PdfNode imagesFolder = buildPageImagesNode(resources, index, ctx);
                if (imagesFolder != null) resourcesNode.addChild(imagesFolder);

                try {
                    COSDictionary resDict = resources.getCOSObject();
                    if (resDict != null) {
                        attachCosChildren(resourcesNode, resDict, "page-" + index + "-resources-cos", ctx, 0);
                    }
                } catch (Exception e) {
                    log.debug("Error attaching COS to resources for page {}", index, e);
                }

                if (!resourcesNode.getChildren().isEmpty()) {
                    pageNode.addChild(resourcesNode);
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing resources for page {}", index, e);
        }

        // Annotations
        try {
            List<PDAnnotation> annotations = page.getAnnotations();
            if (annotations != null && !annotations.isEmpty()) {
                PdfNode annotsNode = new PdfNode("page-" + index + "-annots", "Annotations (" + annotations.size() + ")", "folder", "fa-sticky-note", "#fd7e14");
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

        // Content stream
        try {
            PdfNode csNode = buildContentStreamNode(page, index, ctx);
            if (!csNode.getChildren().isEmpty()) {
                pageNode.addChild(csNode);
            }
        } catch (Exception e) {
            log.warn("Error parsing content stream for page {}", index, e);
        }

        // Raw COS of page
        try {
            attachCosChildren(pageNode, page.getCOSObject(), "page-" + index + "-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to page {}", index, e);
        }

        return pageNode;
    }

    private PdfNode buildPageFontsNode(PDResources resources, int pageIndex, ParseContext ctx) {
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
                        attachCosChildren(fontNode, font.getCOSObject(), "page-" + pageIndex + "-font-" + fontName.getName() + "-cos", ctx, 0);
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
        PdfNode folder = new PdfNode("page-" + pageIndex + "-fonts", "Fonts (" + fontNodes.size() + ")", "folder", "fa-font", "#20c997");
        folder.setNodeCategory("fonts");
        folder.setPageIndex(pageIndex);
        fontNodes.forEach(folder::addChild);
        return folder;
    }

    private PdfNode buildPageImagesNode(PDResources resources, int pageIndex, ParseContext ctx) {
        List<PdfNode> imageNodes = new ArrayList<>();
        try {
            for (COSName xobjName : resources.getXObjectNames()) {
                try {
                    PDXObject xobj = resources.getXObject(xobjName);
                    if (xobj instanceof PDImageXObject) {
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

                        try {
                            attachCosChildren(imgNode, img.getCOSObject(), "page-" + pageIndex + "-img-" + xobjName.getName() + "-cos", ctx, 0);
                        } catch (Exception e) {
                            log.debug("Error attaching COS to image {}", xobjName.getName(), e);
                        }

                        imageNodes.add(imgNode);
                    }
                } catch (Exception e) {
                    log.debug("Could not parse XObject {}", xobjName.getName(), e);
                }
            }
        } catch (Exception e) {
            log.debug("Error iterating XObjects for page {}", pageIndex, e);
        }

        if (imageNodes.isEmpty()) return null;
        PdfNode folder = new PdfNode("page-" + pageIndex + "-images", "Images (" + imageNodes.size() + ")", "folder", "fa-images", "#e83e8c");
        folder.setNodeCategory("images");
        folder.setPageIndex(pageIndex);
        imageNodes.forEach(folder::addChild);
        return folder;
    }

    // ======================== SEMANTIC: ANNOTATIONS ========================

    private PdfNode buildAnnotationNode(PDAnnotation annot, int pageIndex, int annotIndex, ParseContext ctx) {
        String subtype = annot.getSubtype();
        String label = (subtype != null ? subtype : "Unknown") + " Annotation";
        PdfNode node = new PdfNode(
                "page-" + pageIndex + "-annot-" + annotIndex,
                label, "annotation", getAnnotationIcon(subtype), "#fd7e14");
        node.setNodeCategory("annotation");
        node.setPageIndex(pageIndex);

        if (subtype != null) node.addProperty("Subtype", subtype);
        if (annot.getContents() != null) node.addProperty("Contents", annot.getContents());
        if (annot.getAnnotationName() != null) node.addProperty("Name", annot.getAnnotationName());

        PDRectangle rect = annot.getRectangle();
        if (rect != null) {
            node.setBoundingBox(new double[]{rect.getLowerLeftX(), rect.getLowerLeftY(), rect.getWidth(), rect.getHeight()});
            node.addProperty("Rectangle", String.format("%.1f, %.1f, %.1f, %.1f",
                    rect.getLowerLeftX(), rect.getLowerLeftY(), rect.getUpperRightX(), rect.getUpperRightY()));
        }

        try {
            attachCosChildren(node, annot.getCOSObject(), "page-" + pageIndex + "-annot-" + annotIndex + "-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to annotation", e);
        }

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
            case "Square": case "Circle": return "fa-shapes";
            case "Line": return "fa-minus";
            case "Ink": return "fa-pen-fancy";
            case "Popup": return "fa-window-restore";
            default: return "fa-sticky-note";
        }
    }

    // ======================== SEMANTIC: ACROFORM ========================

    private PdfNode buildAcroFormTree(PDAcroForm acroForm, ParseContext ctx) {
        List<PDField> fields = acroForm.getFields();
        PdfNode formNode = new PdfNode("acroform", "AcroForm (" + fields.size() + " fields)", "acroform", "fa-wpforms", "#0dcaf0");
        formNode.setNodeCategory("acroform");

        if (acroForm.isSignaturesExist()) {
            formNode.addProperty("SignaturesExist", "true");
        }
        formNode.addProperty("NeedAppearances", String.valueOf(acroForm.getNeedAppearances()));

        for (PDField field : fields) {
            formNode.addChild(buildFieldNode(field, ctx));
        }

        try {
            attachCosChildren(formNode, acroForm.getCOSObject(), "acroform-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to acroform", e);
        }

        return formNode;
    }

    private PdfNode buildFieldNode(PDField field, ParseContext ctx) {
        String fieldType = field.getFieldType();
        String icon = getFieldIcon(fieldType);
        String label = (field.getPartialName() != null ? field.getPartialName() : "(unnamed)") + " [" + fieldType + "]";

        PdfNode node = new PdfNode(
                "field-" + field.getFullyQualifiedName(),
                label, "field", icon, "#0dcaf0");
        node.setNodeCategory("field");

        node.addProperty("FieldType", fieldType);
        if (field.getFullyQualifiedName() != null) node.addProperty("FullName", field.getFullyQualifiedName());
        if (field.getValueAsString() != null && !field.getValueAsString().isEmpty()) {
            node.addProperty("Value", field.getValueAsString());
        }
        node.addProperty("ReadOnly", String.valueOf(field.isReadOnly()));
        node.addProperty("Required", String.valueOf(field.isRequired()));

        if (!field.getWidgets().isEmpty()) {
            PDRectangle rect = field.getWidgets().get(0).getRectangle();
            if (rect != null) {
                node.setBoundingBox(new double[]{rect.getLowerLeftX(), rect.getLowerLeftY(), rect.getWidth(), rect.getHeight()});
            }
        }

        if (field instanceof PDNonTerminalField) {
            PDNonTerminalField ntField = (PDNonTerminalField) field;
            for (PDField child : ntField.getChildren()) {
                node.addChild(buildFieldNode(child, ctx));
            }
        }

        try {
            attachCosChildren(node, field.getCOSObject(), "field-" + field.getFullyQualifiedName() + "-cos", ctx, 0);
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

    // ======================== SEMANTIC: BOOKMARKS ========================

    private PdfNode buildOutlineTree(PDDocumentOutline outline, ParseContext ctx) {
        PdfNode bookmarksNode = new PdfNode("bookmarks", "Bookmarks", "bookmarks", "fa-bookmark", "#6610f2");
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

    private PdfNode buildOutlineItemNode(PDOutlineItem item, int index, ParseContext ctx) {
        String title = item.getTitle() != null ? item.getTitle() : "(untitled)";
        PdfNode node = new PdfNode(
                "bookmark-" + index + "-" + title.hashCode(),
                title, "bookmark", "fa-bookmark", "#6610f2");
        node.setNodeCategory("bookmark");

        if (item.isBold()) node.addProperty("Bold", "true");
        if (item.isItalic()) node.addProperty("Italic", "true");

        // Extract destination and page index
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
            attachCosChildren(node, item.getCOSObject(), "bookmark-" + index + "-cos", ctx, 0);
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
}
