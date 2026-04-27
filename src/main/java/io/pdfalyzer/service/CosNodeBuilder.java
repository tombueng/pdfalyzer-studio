package io.pdfalyzer.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.stereotype.Component;

import io.pdfalyzer.model.PdfNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds raw COS tree nodes and provides COS-level utility methods.
 * Used by both the semantic tree builder and the raw COS view.
 */
@Component
@Slf4j
public class CosNodeBuilder {

    private static final int MAX_STREAM_PREVIEW = 512;

    // ======================== PARSE CONTEXT ========================

    /**
     * Per-parse mutable context: shared visited set and node cache.
     */
    public static class ParseContext {
        public final Set<COSObjectKey> visited = new HashSet<>();
        public final Map<COSObjectKey, PdfNode> cache = new HashMap<>();
        public final int maxCosDepth;
        public final boolean readStreamData;
        public final PDDocument doc;

        public ParseContext(PDDocument doc, int maxCosDepth, boolean readStreamData) {
            this.doc = doc;
            this.maxCosDepth = maxCosDepth;
            this.readStreamData = readStreamData;
        }
    }

    // ======================== PUBLIC API ========================

    public PdfNode buildRawCosTree(PDDocument doc) {
        ParseContext ctx = new ParseContext(doc, 50, true);
        PdfNode root = new PdfNode("raw-cos", "All COS Objects", "root", "fa-database", "#e74c3c");
        root.setNodeCategory("raw-cos");

        COSDocument cosDoc = doc.getDocument();
        List<COSObjectKey> sortedKeys = new ArrayList<>(cosDoc.getXrefTable().keySet());
        sortedKeys.sort(Comparator.comparingLong(COSObjectKey::getNumber));

        for (COSObjectKey key : sortedKeys) {
            try {
                COSObject obj = cosDoc.getObjectFromPool(key);
                if (obj == null || obj.getObject() == null) continue;
                String label = "obj " + key.getNumber() + " " + key.getGeneration();
                String idPrefix = "cos-" + key.getNumber() + "-" + key.getGeneration();
                Set<COSObjectKey> localVisited = new HashSet<>();
                PdfNode node = buildCosNode(obj.getObject(), label, idPrefix,
                        localVisited, 0, new ArrayList<>(), ctx);
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

    /**
     * Inline COS dictionary entries directly as children of the given parent node.
     * No wrapper "Raw COS Data" node is created — each key becomes a direct child.
     */
    public void attachCosChildren(PdfNode parent, COSDictionary dict,
                                   String idPrefix, ParseContext ctx, int depth) {
        attachCosChildren(parent, dict, idPrefix, ctx, depth, Set.of());
    }

    /**
     * Inline COS dictionary entries directly as children of the given parent node,
     * skipping any keys in {@code excludeKeys} (already represented by semantic nodes).
     */
    public void attachCosChildren(PdfNode parent, COSDictionary dict,
                                   String idPrefix, ParseContext ctx, int depth,
                                   Set<String> excludeKeys) {
        COSObjectKey objKey = null;
        try {
            objKey = dict.getKey();
        } catch (Exception e) {
            log.trace("dict.getKey() threw, falling back to document index", e);
        }
        if (objKey == null) objKey = findObjectKeyInDocument(dict, ctx.doc);

        for (Map.Entry<COSName, COSBase> entry : dict.entrySet()) {
            String key = entry.getKey().getName();
            if (excludeKeys.contains(key)) continue;
            List<String> childPath = new ArrayList<>();
            childPath.add(key);
            PdfNode child = buildCosNode(entry.getValue(), "/" + key,
                    idPrefix + "-" + key, ctx.visited, depth + 1, childPath, ctx);
            if (objKey != null && child.getObjectNumber() < 0) {
                propagateObjectInfo(child, (int) objKey.getNumber(), objKey.getGeneration());
            }
            parent.addChild(child);
        }
    }

    public PdfNode buildCosNode(COSBase obj, String label, String idPrefix,
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

        if (obj instanceof COSObject) {
            return handleCosObject((COSObject) obj, label, idPrefix, visited, depth, currentKeyPath, ctx);
        }
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
            return handleCosString((COSString) obj, label, idPrefix, currentKeyPath);
        }
        if (obj instanceof COSName) {
            String val = ((COSName) obj).getName();
            return makePrimitiveNode(idPrefix, label + ": /" + val, "COSName", val,
                    true, "name", "fa-slash", "#e67e22", currentKeyPath);
        }
        if (obj instanceof COSArray) {
            return handleCosArray((COSArray) obj, label, idPrefix, visited, depth, currentKeyPath, ctx);
        }
        if (obj instanceof COSStream) {
            return handleCosStream((COSStream) obj, label, idPrefix, visited, depth, currentKeyPath, ctx);
        }
        if (obj instanceof COSDictionary) {
            return handleCosDictionary((COSDictionary) obj, label, idPrefix, visited, depth, currentKeyPath, ctx);
        }
        return makeLeafNode(idPrefix, label + ": " + obj.toString(),
                "unknown", obj.toString(), "fa-question", "#999");
    }

    // ======================== CONTENT STREAM ========================

    public PdfNode buildContentStreamNode(PDPage page, int pageIndex, ParseContext ctx) {
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
                    String opName = ((Operator) token).getName();
                    PdfNode opNode = new PdfNode(
                            "page-" + pageIndex + "-op-" + opIndex,
                            opName + " (" + getOperatorDescription(opName) + ")",
                            "Operator", "fa-cog", "#ff69b4");
                    opNode.setCosType("Operator");
                    opNode.setNodeCategory("cos");
                    opNode.setPageIndex(pageIndex);
                    opNode.setRawValue(opName);
                    for (int i = 0; i < operands.size(); i++) {
                        opNode.addChild(buildCosNode(operands.get(i), "operand[" + i + "]",
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

    // ======================== PROPAGATION ========================

    public void propagateObjectInfo(PdfNode node, int objNum, int genNum) {
        if (node.getObjectNumber() < 0) {
            node.setObjectNumber(objNum);
            node.setGenerationNumber(genNum);
        }
        for (PdfNode child : node.getChildren()) {
            propagateObjectInfo(child, objNum, genNum);
        }
    }

    // ======================== PRIVATE HELPERS ========================

    private PdfNode handleCosObject(COSObject cosObj, String label, String idPrefix,
                                     Set<COSObjectKey> visited, int depth,
                                     List<String> currentKeyPath, ParseContext ctx) {
        COSObjectKey key = cosObj.getKey();
        if (key == null) {
            return buildCosNode(cosObj.getObject(), label, idPrefix,
                    visited, depth + 1, currentKeyPath, ctx);
        }
        String objLabel = label + " (obj " + key.getNumber() + " " + key.getGeneration() + " R)";

        // Check cache first — if already fully built, shallow-copy so the node is expandable
        PdfNode cached = ctx.cache.get(key);
        if (cached != null) {
            String suffix = "-ref-" + (idPrefix.hashCode() & 0x7fffffff);
            PdfNode copy = cached.deepCopy(suffix, 4);
            copy.setName(objLabel);
            copy.setKeyPath(keyPathToJson(currentKeyPath));
            return copy;
        }

        // True cycle: object is currently being built in this branch — cannot expand
        if (visited.contains(key)) {
            PdfNode ref = makeLeafNode(idPrefix, objLabel + " [circular ref]",
                    "COSObject", "-> obj " + key.getNumber(), "fa-link", "#ffffff");
            ref.setObjectNumber((int) key.getNumber());
            ref.setGenerationNumber(key.getGeneration());
            ref.addProperty("refTarget", String.valueOf(key.getNumber()));
            ref.setKeyPath(keyPathToJson(currentKeyPath));
            return ref;
        }

        visited.add(key);
        // Reset keyPath for children: they are relative to this object, not the parent
        PdfNode node = buildCosNode(cosObj.getObject(), objLabel, idPrefix,
                visited, depth + 1, new ArrayList<>(), ctx);
        node.setObjectNumber((int) key.getNumber());
        node.setGenerationNumber(key.getGeneration());
        node.setKeyPath(keyPathToJson(currentKeyPath));
        ctx.cache.put(key, node);
        return node;
    }

    private PdfNode handleCosString(COSString cosStr, String label, String idPrefix,
                                     List<String> currentKeyPath) {
        String val = cosStr.getString();
        boolean isBinary = containsBinaryData(cosStr.getBytes());
        String displayVal = isBinary ? cosStr.toHexString() : val;
        String vType = isBinary ? "hex-string" : "string";
        return makePrimitiveNode(idPrefix, label + ": \"" + truncate(displayVal, 60) + "\"",
                "COSString", displayVal, true, vType,
                "fa-quote-right", "#2ecc71", currentKeyPath);
    }

    private PdfNode handleCosArray(COSArray arr, String label, String idPrefix,
                                    Set<COSObjectKey> visited, int depth,
                                    List<String> currentKeyPath, ParseContext ctx) {
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

    private PdfNode handleCosStream(COSStream stream, String label, String idPrefix,
                                     Set<COSObjectKey> visited, int depth,
                                     List<String> currentKeyPath, ParseContext ctx) {
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
        if (ctx.readStreamData) {
            addStreamDataPreview(node, stream, idPrefix, ctx);
        }
        return node;
    }

    private void addStreamDataPreview(PdfNode parent, COSStream stream, String idPrefix,
                                       ParseContext ctx) {
        try {
            byte[] data;
            try (java.io.InputStream is = stream.createInputStream()) {
                data = is.readAllBytes();
            }
            if (data.length == 0) return;

            // Try to parse as content stream (operators + operands)
            if (isContentStream(stream) && isTextContent(data)) {
                try {
                    PDFStreamParser parser = new PDFStreamParser(data);
                    List<Object> tokens = parser.parse();
                    if (hasOperators(tokens)) {
                        PdfNode decoded = new PdfNode(idPrefix + "-decoded",
                                "Decoded Stream Content", "content-stream", "fa-code", "#ff6b6b");
                        decoded.setNodeCategory("cos-stream-decoded");
                        decoded.addProperty("Decoded Length", String.valueOf(data.length));
                        List<COSBase> operands = new ArrayList<>();
                        int opIndex = 0;
                        for (Object token : tokens) {
                            if (token instanceof Operator) {
                                String opName = ((Operator) token).getName();
                                PdfNode opNode = new PdfNode(
                                        idPrefix + "-op-" + opIndex,
                                        opName + " (" + getOperatorDescription(opName) + ")",
                                        "Operator", "fa-cog", "#ff69b4");
                                opNode.setCosType("Operator");
                                opNode.setNodeCategory("cos");
                                opNode.setRawValue(opName);
                                for (int i = 0; i < operands.size(); i++) {
                                    opNode.addChild(buildCosNode(operands.get(i), "operand[" + i + "]",
                                            idPrefix + "-op-" + opIndex + "-arg-" + i,
                                            new HashSet<>(), 0, new ArrayList<>(), ctx));
                                }
                                operands.clear();
                                decoded.addChild(opNode);
                                opIndex++;
                            } else if (token instanceof COSBase) {
                                operands.add((COSBase) token);
                            }
                        }
                        decoded.setName("Decoded Stream Content (" + opIndex + " operators)");
                        parent.addChild(decoded);
                        return;
                    }
                } catch (Exception e) {
                    log.debug("Stream {} not parseable as content stream, falling back to preview", idPrefix);
                }
            }

            // Fallback: hex/text preview for non-content streams
            PdfNode dataNode = new PdfNode(idPrefix + "-data",
                    "Stream Data", "COSStream", "fa-database", "#e74c3c");
            dataNode.setCosType("COSStream");
            dataNode.setNodeCategory("cos-stream-data");
            dataNode.addProperty("Length", String.valueOf(data.length));
            int previewLen = Math.min(data.length, MAX_STREAM_PREVIEW);
            dataNode.addProperty("Preview (hex)", bytesToHex(data, previewLen));
            if (isTextContent(data)) {
                dataNode.addProperty("Preview (text)",
                        new String(data, 0, previewLen, StandardCharsets.ISO_8859_1));
            }
            parent.addChild(dataNode);
        } catch (Exception e) {
            log.debug("Could not read stream data for {}", idPrefix, e);
        }
    }

    /**
     * Determines whether a COSStream is likely a content stream (page, form XObject,
     * Type1 charproc, tiling pattern) whose decoded bytes contain PDF operators.
     */
    private boolean isContentStream(COSStream stream) {
        String type = stream.getNameAsString(COSName.TYPE);
        String subtype = stream.getNameAsString(COSName.SUBTYPE);
        // Form XObjects and tiling patterns contain operators
        if ("XObject".equals(type) && "Form".equals(subtype)) return true;
        if ("Pattern".equals(type)) return true;
        // Page content streams have no /Type — they are direct stream objects
        // referenced from /Contents. Detect by absence of image/font/metadata markers.
        if (type == null && subtype == null) {
            // Exclude streams that have /Width+/Height (images) or /Length1 (fonts)
            if (stream.containsKey(COSName.WIDTH)
                    && stream.containsKey(COSName.HEIGHT)) return false;
            if (stream.containsKey(COSName.getPDFName("Length1"))) return false;
            return true;
        }
        return false;
    }

    private boolean hasOperators(List<Object> tokens) {
        for (Object token : tokens) {
            if (token instanceof Operator) return true;
        }
        return false;
    }

    private PdfNode handleCosDictionary(COSDictionary dict, String label, String idPrefix,
                                         Set<COSObjectKey> visited, int depth,
                                         List<String> currentKeyPath, ParseContext ctx) {
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

    // ======================== FACTORIES ========================

    public PdfNode makePrimitiveNode(String id, String name, String cosType, String rawValue,
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

    public PdfNode makeLeafNode(String id, String name, String cosType,
                                 String rawValue, String icon, String color) {
        PdfNode node = new PdfNode(id, name, cosType, icon, color);
        node.setCosType(cosType);
        node.setRawValue(rawValue);
        node.setNodeCategory("cos");
        return node;
    }

    // ======================== UTILITIES ========================

    public String keyPathToJson(List<String> keyPath) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keyPath.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(keyPath.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    public COSObjectKey findObjectKeyInDocument(COSBase target, PDDocument doc) {
        if (target == null || doc == null) return null;
        try {
            COSDocument cosDoc = doc.getDocument();
            for (COSObjectKey key : cosDoc.getXrefTable().keySet()) {
                COSObject candidate = cosDoc.getObjectFromPool(key);
                if (candidate != null && candidate.getObject() == target) return key;
            }
        } catch (Exception e) {
            log.debug("Could not locate object key via xref scan", e);
        }
        return null;
    }

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

    public String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    public String getOperatorDescription(String op) {
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
            case "Do": return "Invoke XObject";
            case "gs": return "Set Graphics State";
            case "f": case "F": return "Fill";
            case "S": return "Stroke";
            case "B": return "Fill and Stroke";
            case "n": return "End Path";
            case "W": return "Clip";
            case "BI": return "Begin Inline Image";
            case "ID": return "Inline Image Data";
            case "EI": return "End Inline Image";
            case "BMC": return "Begin Marked Content";
            case "BDC": return "Begin Marked Content (props)";
            case "EMC": return "End Marked Content";
            case "RG": return "Set RGB (stroke)";
            case "rg": return "Set RGB (fill)";
            case "G": return "Set Gray (stroke)";
            case "g": return "Set Gray (fill)";
            case "K": return "Set CMYK (stroke)";
            case "k": return "Set CMYK (fill)";
            case "w": return "Set Line Width";
            case "J": return "Set Line Cap";
            case "j": return "Set Line Join";
            default: return op;
        }
    }
}
