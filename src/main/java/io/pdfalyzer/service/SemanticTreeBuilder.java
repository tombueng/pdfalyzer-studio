package io.pdfalyzer.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
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
        CosNodeBuilder.ParseContext ctx = new CosNodeBuilder.ParseContext(doc, 50, false);

        PdfNode root = new PdfNode("catalog", "Document Catalog", "root", "fa-book", "#dc3545");
        root.setNodeCategory("catalog");

        // Keys handled by semantic builders or addCatalogDictionaryNodes — skip in COS inlining
        Set<String> catalogExcludeKeys = Set.of(
                "Pages", "AcroForm", "Outlines", "Names",
                "DSS", "Extensions", "Perms", "MarkInfo",
                "ViewerPreferences", "Metadata", "StructTreeRoot",
                "Type" // /Type=Catalog is self-evident
        );
        try {
            cosBuilder.attachCosChildren(root, doc.getDocumentCatalog().getCOSObject(),
                    "catalog-cos", ctx, 0, catalogExcludeKeys);
        } catch (Exception e) {
            log.warn("Error attaching COS to catalog", e);
        }

        PDDocumentInformation info = doc.getDocumentInformation();
        if (info != null)
            root.addChild(buildDocInfoNode(info, ctx));

        root.addChild(buildPagesTree(doc, ctx));

        // Build DSS first to get serialToBadge map for cross-referencing sig fields
        Map<String, String> serialToBadge = Collections.emptyMap();
        try {
            COSDictionary catalogDict = doc.getDocumentCatalog().getCOSObject();
            COSBase dssValue = catalogDict.getDictionaryObject(COSName.getPDFName("DSS"));
            if (dssValue instanceof COSDictionary dssDict) {
                DocumentStructureTreeBuilder.DssResult dssResult =
                        structureTreeBuilder.buildDssNode(dssDict, ctx);
                root.addChild(dssResult.node());
                serialToBadge = dssResult.serialToBadge();
            }
        } catch (Exception e) {
            log.debug("Error building DSS node", e);
        }

        try {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null)
                root.addChild(acroFormTreeBuilder.buildAcroFormTree(acroForm, ctx, serialToBadge));
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

        addCatalogDictionaryNodes(doc, root, ctx);

        return root;
    }

    /**
     * Promotes important catalog-level dictionary entries to top-level semantic nodes.
     * DSS gets specialized decoding (certs, CRLs, OCSPs); others get inline COS children.
     */
    private void addCatalogDictionaryNodes(PDDocument doc, PdfNode root, CosNodeBuilder.ParseContext ctx) {
        COSDictionary catalogDict = doc.getDocumentCatalog().getCOSObject();

        // DSS is built earlier (before AcroForm) to get serialToBadge for signature badge matching

        // Permissions with specialized DocMDP decoding
        try {
            COSBase permsValue = catalogDict.getDictionaryObject(COSName.PERMS);
            if (permsValue instanceof COSDictionary permsDict) {
                root.addChild(buildPermsNode(permsDict, ctx));
            }
        } catch (Exception e) {
            log.debug("Error building Permissions node", e);
        }

        // Extensions
        try {
            COSBase extValue = catalogDict.getDictionaryObject(COSName.getPDFName("Extensions"));
            if (extValue != null) root.addChild(buildExtensionsNode(extValue, ctx));
        } catch (Exception e) {
            log.debug("Error building Extensions node", e);
        }

        // MarkInfo
        try {
            COSBase markValue = catalogDict.getDictionaryObject(COSName.getPDFName("MarkInfo"));
            if (markValue != null) root.addChild(buildMarkInfoNode(markValue, ctx));
        } catch (Exception e) {
            log.debug("Error building MarkInfo node", e);
        }

        // ViewerPreferences
        try {
            COSBase vpValue = catalogDict.getDictionaryObject(COSName.getPDFName("ViewerPreferences"));
            if (vpValue != null) root.addChild(buildViewerPreferencesNode(vpValue, ctx));
        } catch (Exception e) {
            log.debug("Error building ViewerPreferences node", e);
        }

        // Metadata (XMP stream)
        try {
            COSBase metaValue = catalogDict.getDictionaryObject(COSName.METADATA);
            if (metaValue != null) root.addChild(buildMetadataNode(metaValue, ctx));
        } catch (Exception e) {
            log.debug("Error building Metadata node", e);
        }

        // StructTreeRoot
        try {
            COSBase structValue = catalogDict.getDictionaryObject(COSName.getPDFName("StructTreeRoot"));
            if (structValue != null) root.addChild(buildStructTreeRootNode(structValue, ctx));
        } catch (Exception e) {
            log.debug("Error building StructTreeRoot node", e);
        }
    }

    /**
     * Builds the Permissions node with human-readable DocMDP decoding.
     * DocMDP (Document Modification Detection and Prevention) specifies what
     * changes are permitted after signing (ISO 32000-1, Table 258).
     */
    private PdfNode buildPermsNode(COSDictionary permsDict, CosNodeBuilder.ParseContext ctx) {
        PdfNode perms = new PdfNode("catalog-perms", "Permissions {" + permsDict.size() + " entries}",
                "catalog-entry", "fa-user-shield", "#fd7e14");
        perms.setNodeCategory("catalog-entry");

        Set<String> permsExcludeKeys = new HashSet<>();

        // Decode /DocMDP — a signature dictionary with /TransformParams containing /P
        COSBase docMdpValue = permsDict.getDictionaryObject(COSName.getPDFName("DocMDP"));
        if (docMdpValue != null) {
            permsExcludeKeys.add("DocMDP");
            COSBase resolved = resolve(docMdpValue);

            PdfNode docMdpNode = new PdfNode("perms-docmdp", "DocMDP (Modification Detection)",
                    "permission", "fa-lock", "#fd7e14");
            docMdpNode.setNodeCategory("permission");

            if (resolved instanceof COSDictionary sigDict) {
                // The signature dictionary may contain /TransformParams with the /P value
                COSBase tpResolved = resolve(sigDict.getDictionaryObject(COSName.getPDFName("TransformParams")));

                int pValue = -1;
                if (tpResolved instanceof COSDictionary tpDict) {
                    pValue = tpDict.getInt(COSName.P, -1);
                }
                // Fallback: /P directly on the signature dict
                if (pValue < 0) {
                    pValue = sigDict.getInt(COSName.P, -1);
                }

                if (pValue > 0) {
                    docMdpNode.addProperty("Permission Level", "P=" + pValue + " — " + docMdpPermissionLabel(pValue));
                } else {
                    docMdpNode.addProperty("Permission Level", "Not specified (default: no restrictions)");
                }

                // Show signer reference info if present
                String sigName = sigDict.getString(COSName.getPDFName("Name"));
                if (sigName != null) docMdpNode.addProperty("Signer", sigName);
                String sigReason = sigDict.getString(COSName.getPDFName("Reason"));
                if (sigReason != null) docMdpNode.addProperty("Reason", sigReason);
                String sigFilter = sigDict.getNameAsString(COSName.FILTER);
                if (sigFilter != null) docMdpNode.addProperty("Filter", sigFilter);
                String sigSubFilter = sigDict.getNameAsString(COSName.getPDFName("SubFilter"));
                if (sigSubFilter != null) docMdpNode.addProperty("SubFilter", sigSubFilter);

                cosBuilder.attachCosChildren(docMdpNode, sigDict, "perms-docmdp-cos", ctx, 0);
            }
            perms.addChild(docMdpNode);
        }

        // Decode /UR3 (Usage Rights) if present
        COSBase ur3Value = permsDict.getDictionaryObject(COSName.getPDFName("UR3"));
        if (ur3Value != null) {
            permsExcludeKeys.add("UR3");
            PdfNode ur3Node = new PdfNode("perms-ur3", "UR3 (Usage Rights)",
                    "permission", "fa-key", "#fd7e14");
            ur3Node.setNodeCategory("permission");
            COSBase ur3Resolved = resolve(ur3Value);
            if (ur3Resolved instanceof COSDictionary ur3Dict) {
                cosBuilder.attachCosChildren(ur3Node, ur3Dict, "perms-ur3-cos", ctx, 0);
            }
            perms.addChild(ur3Node);
        }

        // Remaining COS entries
        cosBuilder.attachCosChildren(perms, permsDict, "perms-cos", ctx, 0, permsExcludeKeys);
        return perms;
    }

    private String docMdpPermissionLabel(int p) {
        switch (p) {
            case 1: return "No changes permitted (certification signature)";
            case 2: return "Form fill-in and signing permitted";
            case 3: return "Form fill-in, signing, and annotation permitted";
            default: return "Unknown permission level (" + p + ")";
        }
    }

    // ── Extensions (ISO 32000-2 §7.12) ────────────────────────────────────

    private PdfNode buildExtensionsNode(COSBase value, CosNodeBuilder.ParseContext ctx) {
        COSBase resolved = resolve(value);
        PdfNode node = new PdfNode("catalog-extensions", "Extensions",
                "catalog-entry", "fa-puzzle-piece", "#6f42c1");
        node.setNodeCategory("catalog-entry");

        if (resolved instanceof COSDictionary extDict) {
            int count = 0;
            Set<String> decoded = new HashSet<>();
            for (Map.Entry<COSName, COSBase> entry : extDict.entrySet()) {
                String nsName = entry.getKey().getName();
                if ("Type".equals(nsName)) { decoded.add(nsName); continue; }
                COSBase nsValue = resolve(entry.getValue());
                if (nsValue instanceof COSDictionary nsDict) {
                    decoded.add(nsName);
                    PdfNode nsNode = new PdfNode("ext-" + nsName, nsName + " Extension",
                            "extension", "fa-puzzle-piece", "#6f42c1");
                    nsNode.setNodeCategory("extension");
                    String baseVersion = nsDict.getNameAsString(COSName.getPDFName("BaseVersion"));
                    if (baseVersion != null) nsNode.addProperty("Base Version", baseVersion);
                    String extLevel = nsDict.getNameAsString(COSName.getPDFName("ExtensionLevel"));
                    if (extLevel == null) {
                        int lvl = nsDict.getInt(COSName.getPDFName("ExtensionLevel"), -1);
                        if (lvl >= 0) extLevel = String.valueOf(lvl);
                    }
                    if (extLevel != null) {
                        nsNode.addProperty("Extension Level", extLevel
                                + extensionLevelDescription(nsName, extLevel));
                    }
                    String url = nsDict.getString(COSName.getPDFName("URL"));
                    if (url != null) nsNode.addProperty("URL", url);
                    cosBuilder.attachCosChildren(nsNode, nsDict, "ext-" + nsName + "-cos", ctx, 0,
                            Set.of("BaseVersion", "ExtensionLevel", "URL", "Type"));
                    node.addChild(nsNode);
                    count++;
                }
            }
            node.setName("Extensions (" + count + " namespaces)");
            cosBuilder.attachCosChildren(node, extDict, "extensions-cos", ctx, 0, decoded);
        } else {
            node.addProperty("Value", String.valueOf(value));
        }
        return node;
    }

    // ── MarkInfo (ISO 32000-2 §14.7) ────────────────────────────────────

    private PdfNode buildMarkInfoNode(COSBase value, CosNodeBuilder.ParseContext ctx) {
        COSBase resolved = resolve(value);
        PdfNode node = new PdfNode("catalog-markinfo", "Mark Info",
                "catalog-entry", "fa-tags", "#17a2b8");
        node.setNodeCategory("catalog-entry");

        if (resolved instanceof COSDictionary markDict) {
            boolean marked = markDict.getBoolean(COSName.getPDFName("Marked"), false);
            boolean suspects = markDict.getBoolean(COSName.getPDFName("Suspects"), false);
            boolean userProps = markDict.getBoolean(COSName.getPDFName("UserProperties"), false);

            node.addProperty("Marked (Tagged PDF)",
                    marked ? "Yes — document is tagged for accessibility" : "No — document is not tagged");
            node.addProperty("Suspects",
                    suspects ? "Yes — tag structure may not be reliable" : "No — tag structure is reliable");
            node.addProperty("User Properties",
                    userProps ? "Yes — structure elements contain user properties" : "No");

            node.setName("Mark Info" + (marked ? " (Tagged)" : " (Not Tagged)"));
            cosBuilder.attachCosChildren(node, markDict, "markinfo-cos", ctx, 0,
                    Set.of("Marked", "Suspects", "UserProperties", "Type"));
        } else {
            node.addProperty("Value", String.valueOf(value));
        }
        return node;
    }

    // ── ViewerPreferences (ISO 32000-2 §12.2) ───────────────────────────

    private PdfNode buildViewerPreferencesNode(COSBase value, CosNodeBuilder.ParseContext ctx) {
        COSBase resolved = resolve(value);
        PdfNode node = new PdfNode("catalog-viewerpreferences", "Viewer Preferences",
                "catalog-entry", "fa-sliders-h", "#6c757d");
        node.setNodeCategory("catalog-entry");

        if (resolved instanceof COSDictionary vpDict) {
            Set<String> decoded = new HashSet<>();
            decoded.add("Type");

            // Boolean preferences
            String[][] boolPrefs = {
                { "HideToolbar", "Hide Toolbar", "Toolbar is hidden", "Toolbar is visible" },
                { "HideMenubar", "Hide Menu Bar", "Menu bar is hidden", "Menu bar is visible" },
                { "HideWindowUI", "Hide Window UI", "Window UI elements hidden", "Window UI elements visible" },
                { "FitWindow", "Fit Window", "Window resized to fit first page", "Default window size" },
                { "CenterWindow", "Center Window", "Window centered on screen", "Default window position" },
                { "DisplayDocTitle", "Display Document Title", "Title bar shows document title", "Title bar shows filename" },
            };
            for (String[] pref : boolPrefs) {
                if (vpDict.containsKey(COSName.getPDFName(pref[0]))) {
                    decoded.add(pref[0]);
                    boolean val = vpDict.getBoolean(COSName.getPDFName(pref[0]), false);
                    node.addProperty(pref[1], val ? pref[2] : pref[3]);
                }
            }

            // Enum/name preferences
            String[][] namePrefs = {
                { "NonFullScreenPageMode", "Non-Fullscreen Page Mode" },
                { "Direction", "Reading Direction" },
                { "PrintScaling", "Print Scaling" },
                { "Duplex", "Duplex Printing" },
                { "ViewArea", "View Area" },
                { "ViewClip", "View Clip" },
                { "PrintArea", "Print Area" },
                { "PrintClip", "Print Clip" },
            };
            for (String[] pref : namePrefs) {
                String nameVal = vpDict.getNameAsString(COSName.getPDFName(pref[0]));
                if (nameVal != null) {
                    decoded.add(pref[0]);
                    node.addProperty(pref[1], decodeViewerPrefValue(pref[0], nameVal));
                }
            }

            // Integer preferences
            if (vpDict.containsKey(COSName.getPDFName("NumCopies"))) {
                decoded.add("NumCopies");
                node.addProperty("Number of Copies",
                        String.valueOf(vpDict.getInt(COSName.getPDFName("NumCopies"), 1)));
            }

            // Print page range
            COSBase printRange = vpDict.getDictionaryObject(COSName.getPDFName("PrintPageRange"));
            if (printRange instanceof COSArray rangeArr) {
                decoded.add("PrintPageRange");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rangeArr.size(); i += 2) {
                    if (sb.length() > 0) sb.append(", ");
                    int from = ((org.apache.pdfbox.cos.COSInteger) rangeArr.get(i)).intValue();
                    int to = (i + 1 < rangeArr.size())
                            ? ((org.apache.pdfbox.cos.COSInteger) rangeArr.get(i + 1)).intValue() : from;
                    sb.append(from).append("-").append(to);
                }
                node.addProperty("Print Page Range", sb.toString());
            }

            int decodedCount = decoded.size() - 1; // minus Type
            node.setName("Viewer Preferences (" + decodedCount + " settings)");
            cosBuilder.attachCosChildren(node, vpDict, "viewerprefs-cos", ctx, 0, decoded);
        } else {
            node.addProperty("Value", String.valueOf(value));
        }
        return node;
    }

    private String decodeViewerPrefValue(String key, String value) {
        switch (key) {
            case "NonFullScreenPageMode":
                switch (value) {
                    case "UseNone": return "Neither outlines nor thumbnails (UseNone)";
                    case "UseOutlines": return "Show outlines panel (UseOutlines)";
                    case "UseThumbs": return "Show thumbnails panel (UseThumbs)";
                    case "UseOC": return "Show optional content panel (UseOC)";
                    default: return value;
                }
            case "Direction":
                switch (value) {
                    case "L2R": return "Left to right (L2R)";
                    case "R2L": return "Right to left (R2L)";
                    default: return value;
                }
            case "PrintScaling":
                switch (value) {
                    case "None": return "No scaling (None)";
                    case "AppDefault": return "Application default scaling (AppDefault)";
                    default: return value;
                }
            case "Duplex":
                switch (value) {
                    case "Simplex": return "Single-sided (Simplex)";
                    case "DuplexFlipShortEdge": return "Duplex, flip on short edge";
                    case "DuplexFlipLongEdge": return "Duplex, flip on long edge";
                    default: return value;
                }
            default: return value;
        }
    }

    // ── Metadata XMP stream (ISO 32000-2 §14.3) ─────────────────────────

    private PdfNode buildMetadataNode(COSBase value, CosNodeBuilder.ParseContext ctx) {
        COSBase resolved = resolve(value);
        PdfNode node = new PdfNode("catalog-metadata", "Metadata (XMP)",
                "catalog-entry", "fa-info", "#20c997");
        node.setNodeCategory("catalog-entry");

        if (resolved instanceof COSStream metaStream) {
            try (InputStream is = metaStream.createInputStream()) {
                byte[] data = is.readAllBytes();
                String xml = new String(data, StandardCharsets.UTF_8);
                parseXmpProperties(node, xml);
                node.addProperty("XMP Size", data.length + " bytes");
            } catch (Exception e) {
                log.debug("Could not parse XMP metadata stream", e);
                node.addProperty("Error", "Could not parse XMP: " + e.getMessage());
            }
            cosBuilder.attachCosChildren(node, metaStream, "metadata-cos", ctx, 0);
        } else if (resolved instanceof COSDictionary metaDict) {
            cosBuilder.attachCosChildren(node, metaDict, "metadata-cos", ctx, 0);
        } else {
            node.addProperty("Value", String.valueOf(value));
        }
        return node;
    }

    /**
     * Lightweight XMP parser — extracts common Dublin Core and PDF properties
     * without pulling in a full XML parser dependency.
     */
    private void parseXmpProperties(PdfNode node, String xml) {
        String[][] xmpFields = {
            { "dc:title", "Title" },
            { "dc:creator", "Creator" },
            { "dc:description", "Description" },
            { "dc:subject", "Subject" },
            { "dc:format", "Format" },
            { "dc:rights", "Rights" },
            { "xmp:CreatorTool", "Creator Tool" },
            { "xmp:CreateDate", "Create Date" },
            { "xmp:ModifyDate", "Modify Date" },
            { "xmp:MetadataDate", "Metadata Date" },
            { "pdf:Producer", "PDF Producer" },
            { "pdf:PDFVersion", "PDF Version" },
            { "pdf:Keywords", "Keywords" },
            { "pdfaid:part", "PDF/A Part" },
            { "pdfaid:conformance", "PDF/A Conformance" },
            { "pdfuaid:part", "PDF/UA Part" },
            { "xmpMM:DocumentID", "Document ID" },
            { "xmpMM:InstanceID", "Instance ID" },
        };
        int found = 0;
        for (String[] field : xmpFields) {
            String val = extractXmpValue(xml, field[0]);
            if (val != null && !val.isBlank()) {
                node.addProperty(field[1], val.trim());
                found++;
            }
        }
        node.setName("Metadata (XMP" + (found > 0 ? ", " + found + " properties" : "") + ")");
    }

    private String extractXmpValue(String xml, String tag) {
        // Try <tag>value</tag> and <tag>...<rdf:li>value</rdf:li>...</tag>
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) {
            // Try attribute form: tag="value"
            String attr = tag.substring(tag.indexOf(':') + 1) + "=\"";
            int aStart = xml.indexOf(attr);
            if (aStart >= 0) {
                int vStart = aStart + attr.length();
                int vEnd = xml.indexOf('"', vStart);
                if (vEnd > vStart) return xml.substring(vStart, vEnd);
            }
            return null;
        }
        int end = xml.indexOf(close, start);
        if (end < 0) return null;
        String content = xml.substring(start + open.length(), end);
        // If it contains rdf:li, extract the first li value
        String liOpen = "<rdf:li";
        int liStart = content.indexOf(liOpen);
        if (liStart >= 0) {
            int gtPos = content.indexOf('>', liStart);
            if (gtPos >= 0) {
                int liEnd = content.indexOf("</rdf:li>", gtPos);
                if (liEnd > gtPos) return content.substring(gtPos + 1, liEnd);
            }
        }
        // Strip any remaining XML tags
        return content.replaceAll("<[^>]+>", "").trim();
    }

    // ── StructTreeRoot (ISO 32000-2 §14.7.2) ────────────────────────────

    private PdfNode buildStructTreeRootNode(COSBase value, CosNodeBuilder.ParseContext ctx) {
        COSBase resolved = resolve(value);
        PdfNode node = new PdfNode("catalog-structtreeroot", "Structure Tree Root",
                "catalog-entry", "fa-sitemap", "#e83e8c");
        node.setNodeCategory("catalog-entry");

        if (resolved instanceof COSDictionary strDict) {
            Set<String> decoded = new HashSet<>();
            decoded.add("Type");

            // RoleMap: custom role -> standard role mapping
            COSBase roleMapValue = strDict.getDictionaryObject(COSName.getPDFName("RoleMap"));
            COSBase roleMapResolved = resolve(roleMapValue);
            if (roleMapResolved instanceof COSDictionary roleMap) {
                decoded.add("RoleMap");
                PdfNode roleNode = new PdfNode("struct-rolemap",
                        "Role Map (" + roleMap.size() + " mappings)",
                        "folder", "fa-exchange-alt", "#e83e8c");
                roleNode.setNodeCategory("struct-rolemap");
                for (Map.Entry<COSName, COSBase> entry : roleMap.entrySet()) {
                    String customRole = entry.getKey().getName();
                    COSBase targetValue = entry.getValue();
                    String stdRole = (targetValue instanceof COSName)
                            ? ((COSName) targetValue).getName() : String.valueOf(targetValue);
                    PdfNode mapping = new PdfNode("rolemap-" + customRole,
                            customRole + " -> " + stdRole,
                            "role-mapping", "fa-arrow-right", "#e83e8c");
                    mapping.setNodeCategory("struct-rolemap-entry");
                    mapping.addProperty("Custom Role", customRole);
                    mapping.addProperty("Standard Role", stdRole);
                    String desc = structureRoleDescription(stdRole);
                    if (desc != null) mapping.addProperty("Description", desc);
                    roleNode.addChild(mapping);
                }
                node.addChild(roleNode);
            }

            // Count structure element children
            COSBase kValue = strDict.getDictionaryObject(COSName.getPDFName("K"));
            if (kValue != null) {
                COSBase kResolved = resolve(kValue);
                if (kResolved instanceof COSArray kArr) {
                    node.addProperty("Root Elements", String.valueOf(kArr.size()));
                } else {
                    node.addProperty("Root Elements", "1");
                }
            }

            // ParentTree reference
            if (strDict.getDictionaryObject(COSName.getPDFName("ParentTree")) != null) {
                node.addProperty("Parent Tree", "Present (number tree mapping marked-content to structure)");
            }

            // IDTree reference
            if (strDict.getDictionaryObject(COSName.getPDFName("IDTree")) != null) {
                node.addProperty("ID Tree", "Present (name tree for element identifiers)");
            }

            node.setName("Structure Tree Root" + (roleMapResolved instanceof COSDictionary rm
                    ? " (" + rm.size() + " role mappings)" : ""));
            cosBuilder.attachCosChildren(node, strDict, "structtreeroot-cos", ctx, 0, decoded);
        } else {
            node.addProperty("Value", String.valueOf(value));
        }
        return node;
    }

    private String structureRoleDescription(String role) {
        switch (role) {
            case "Document": return "Entire document";
            case "Part": return "Large division of a document";
            case "Art": return "Article";
            case "Sect": return "Section";
            case "Div": return "Generic block-level grouping";
            case "BlockQuote": return "Block quotation";
            case "Caption": return "Caption for a figure or table";
            case "TOC": return "Table of contents";
            case "TOCI": return "Table of contents item";
            case "Index": return "Index";
            case "P": return "Paragraph";
            case "H": return "Heading (auto-level)";
            case "H1": case "H2": case "H3": case "H4": case "H5": case "H6":
                return "Heading level " + role.substring(1);
            case "L": return "List";
            case "LI": return "List item";
            case "Lbl": return "Label (e.g., bullet or number)";
            case "LBody": return "List item body";
            case "Table": return "Table";
            case "TR": return "Table row";
            case "TH": return "Table header cell";
            case "TD": return "Table data cell";
            case "THead": return "Table header group";
            case "TBody": return "Table body group";
            case "TFoot": return "Table footer group";
            case "Span": return "Inline text span";
            case "Link": return "Hyperlink";
            case "Annot": return "Annotation";
            case "Figure": return "Figure (image or graphic)";
            case "Formula": return "Mathematical formula";
            case "Form": return "Form widget";
            case "Ruby": return "Ruby annotation (CJK)";
            case "Warichu": return "Warichu annotation (CJK)";
            default: return null;
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────

    /**
     * Provides human-readable context for known PDF extension levels.
     * Adobe (ADBE) extension levels map to specific Acrobat/PDF feature sets.
     */
    private String extensionLevelDescription(String namespace, String level) {
        if ("ADBE".equals(namespace)) {
            switch (level) {
                case "3": return " — Adobe Extension Level 3 (Acrobat 9.0 features: XFA 3.0, rich media)";
                case "5": return " — Adobe Extension Level 5 (Acrobat 9.1 features: AES-256 encryption)";
                case "6": return " — Adobe Extension Level 6 (Acrobat X features: enhanced XFA)";
                case "7": return " — Adobe Extension Level 7 (Acrobat X features: enhanced encryption)";
                case "8": return " — Adobe Extension Level 8 (Acrobat XI features)";
                default: return " — Adobe Extension Level " + level;
            }
        }
        // ISO extensions (e.g., ISO TS 32001, 32002)
        return " — extension features beyond base PDF version";
    }

    private COSBase resolve(COSBase value) {
        if (value instanceof COSObject) return ((COSObject) value).getObject();
        return value;
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
