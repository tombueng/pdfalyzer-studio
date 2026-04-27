package io.pdfalyzer.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
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
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.SingleResp;
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

    // ── DSS (Document Security Store) ─────────────────────────────────────

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");

    /** Badge colors for linking certs ↔ OCSPs ↔ CRLs (up to 12 distinct colors). */
    private static final String[] BADGE_COLORS = {
        "#0d6efd", "#198754", "#dc3545", "#fd7e14", "#6f42c1", "#20c997",
        "#d63384", "#0dcaf0", "#ffc107", "#6610f2", "#e83e8c", "#17a2b8"
    };

    /** Result of building the DSS node, including the cross-reference badge map. */
    record DssResult(PdfNode node, Map<String, String> serialToBadge) {}

    /** Parsed certificate with its original COS object and array index. */
    private record DssCert(X509Certificate cert, COSBase cosObj, int arrayIndex, byte[] derBytes) {}

    DssResult buildDssNode(COSDictionary dssDict, CosNodeBuilder.ParseContext ctx) {
        PdfNode dss = new PdfNode("catalog-dss", "Document Security Store (DSS)",
                "catalog-entry", "fa-shield-alt", "#28a745");
        dss.setNodeCategory("catalog-entry");

        // ── Step 1: parse all certificates ──────────────────────────────
        COSBase certsBase = dssDict.getDictionaryObject(COSName.getPDFName("Certs"));
        COSArray certsArray = certsBase instanceof COSArray ca ? ca : null;
        List<DssCert> parsedCerts = new ArrayList<>();
        if (certsArray != null) {
            CertificateFactory cf;
            try { cf = CertificateFactory.getInstance("X.509"); } catch (Exception e) { cf = null; }
            for (int i = 0; i < certsArray.size(); i++) {
                byte[] data = readStreamBytes(certsArray.get(i));
                if (data == null || cf == null) continue;
                try {
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(data));
                    parsedCerts.add(new DssCert(cert, certsArray.get(i), i, data));
                } catch (Exception e) {
                    log.debug("Could not parse certificate #{} for chain building", i, e);
                }
            }
        }

        // ── Step 2: deduplicate certs by serial+issuer ────────────────────
        // Key = "serial|issuerDN" → first occurrence (canonical representative)
        Map<String, DssCert> uniqueCerts = new LinkedHashMap<>();
        Map<String, List<Integer>> dupeIndices = new HashMap<>();  // key → all array indices
        for (DssCert dc : parsedCerts) {
            String key = dc.cert().getSerialNumber().toString(16).toUpperCase()
                    + "|" + dc.cert().getIssuerX500Principal().getName();
            dupeIndices.computeIfAbsent(key, k -> new ArrayList<>()).add(dc.arrayIndex());
            if (!uniqueCerts.containsKey(key)) {
                uniqueCerts.put(key, dc);
            }
        }
        List<DssCert> dedupedCerts = new ArrayList<>(uniqueCerts.values());

        // ── Step 3: build cert tree and assign badge numbers ─────────────
        // Map: cert subject DN → list of unique certs with that subject
        Map<String, List<DssCert>> bySubject = new LinkedHashMap<>();
        for (DssCert dc : dedupedCerts) {
            bySubject.computeIfAbsent(dc.cert().getSubjectX500Principal().getName(), k -> new ArrayList<>()).add(dc);
        }
        // Maps for cross-referencing badges
        Map<String, String> serialToBadge = new HashMap<>();
        Map<String, String> subjectDnToBadge = new HashMap<>();
        int badgeCounter = 1;

        // Find root certs (self-signed or whose issuer is not in the set)
        List<DssCert> roots = new ArrayList<>();
        java.util.Set<String> placedKeys = new java.util.HashSet<>();
        for (DssCert dc : dedupedCerts) {
            boolean isSelfSigned = dc.cert().getSubjectX500Principal().equals(dc.cert().getIssuerX500Principal());
            boolean issuerPresent = bySubject.containsKey(dc.cert().getIssuerX500Principal().getName())
                    && !isSelfSigned;
            if (!issuerPresent) {
                roots.add(dc);
            }
        }

        // Assign badge numbers depth-first (roots first, then their children, etc.)
        // We'll assign during tree node creation below, so first just collect all certs in tree order
        List<DssCert> treeOrder = new ArrayList<>();
        collectTreeOrder(roots, dedupedCerts, placedKeys, treeOrder);
        // Any orphans not placed in the tree
        List<DssCert> orphans = new ArrayList<>();
        for (DssCert dc : dedupedCerts) {
            if (!placedKeys.contains(certDedupeKey(dc))) orphans.add(dc);
        }

        // Assign badge numbers in tree order, then orphans
        for (DssCert dc : treeOrder) {
            String badge = String.valueOf(badgeCounter++);
            String serial = dc.cert().getSerialNumber().toString(16).toUpperCase();
            serialToBadge.put(serial, badge);
            subjectDnToBadge.put(dc.cert().getSubjectX500Principal().getName(), badge);
        }
        for (DssCert dc : orphans) {
            String badge = String.valueOf(badgeCounter++);
            String serial = dc.cert().getSerialNumber().toString(16).toUpperCase();
            serialToBadge.put(serial, badge);
            subjectDnToBadge.put(dc.cert().getSubjectX500Principal().getName(), badge);
        }

        // ── Step 4: build certificate chain tree (proper tree, not linear) ──
        int totalRaw = certsArray != null ? certsArray.size() : 0;
        int totalUnique = dedupedCerts.size();
        String certsLabel = totalRaw == totalUnique
                ? "Certificate Chain (" + totalRaw + " certificates)"
                : "Certificate Chain (" + totalUnique + " unique, " + totalRaw + " in PDF)";
        PdfNode certsNode = new PdfNode("dss-certs", certsLabel, "folder", "fa-certificate", "#28a745");
        certsNode.setNodeCategory("dss-certs");

        for (DssCert root : roots) {
            PdfNode rootNode = buildCertTreeNode(root, dedupedCerts, serialToBadge, dupeIndices,
                    new java.util.HashSet<>(), ctx);
            certsNode.addChild(rootNode);
        }
        for (DssCert dc : orphans) {
            certsNode.addChild(buildDssCertNode(dc, serialToBadge, dupeIndices, ctx));
        }
        // Add nodes for certs that failed to parse
        if (certsArray != null) {
            java.util.Set<Integer> allPlacedIndices = new java.util.HashSet<>();
            for (DssCert dc : parsedCerts) allPlacedIndices.add(dc.arrayIndex());
            for (int i = 0; i < certsArray.size(); i++) {
                if (!allPlacedIndices.contains(i)) {
                    certsNode.addChild(buildUnreadableCertNode(certsArray.get(i), i, ctx));
                }
            }
        }
        if (totalRaw > 0) dss.addChild(certsNode);

        // ── Step 4: build OCSP nodes with badge matching ────────────────
        COSBase ocspsBase = dssDict.getDictionaryObject(COSName.getPDFName("OCSPs"));
        COSArray ocsps = ocspsBase instanceof COSArray oa ? oa : null;
        if (ocsps != null) {
            PdfNode ocspsNode = new PdfNode("dss-ocsps",
                    "OCSP Responses (" + ocsps.size() + ")", "folder", "fa-check-circle", "#17a2b8");
            ocspsNode.setNodeCategory("dss-ocsps");
            for (int i = 0; i < ocsps.size(); i++) {
                ocspsNode.addChild(buildDssOcspNode(ocsps.get(i), i, serialToBadge, ctx));
            }
            dss.addChild(ocspsNode);
        }

        // ── Step 5: build CRL nodes with badge matching ─────────────────
        COSBase crlsBase = dssDict.getDictionaryObject(COSName.getPDFName("CRLs"));
        COSArray crls = crlsBase instanceof COSArray cra ? cra : null;
        if (crls != null) {
            PdfNode crlsNode = new PdfNode("dss-crls",
                    "CRLs (" + crls.size() + ")", "folder", "fa-ban", "#dc3545");
            crlsNode.setNodeCategory("dss-crls");
            for (int i = 0; i < crls.size(); i++) {
                crlsNode.addChild(buildDssCrlNode(crls.get(i), i, subjectDnToBadge, ctx));
            }
            dss.addChild(crlsNode);
        }

        // ── Step 6: VRI ─────────────────────────────────────────────────
        COSBase vri = dssDict.getDictionaryObject(COSName.getPDFName("VRI"));
        if (vri instanceof COSDictionary vriDict) {
            PdfNode vriNode = new PdfNode("dss-vri",
                    "VRI (Validation Related Info) {" + vriDict.size() + " entries}",
                    "folder", "fa-fingerprint", "#6f42c1");
            vriNode.setNodeCategory("dss-vri");
            for (Map.Entry<COSName, COSBase> entry : vriDict.entrySet()) {
                String sigHash = entry.getKey().getName();
                COSBase sigVri = entry.getValue();
                PdfNode sigNode = new PdfNode("dss-vri-" + sigHash,
                        "Signature " + sigHash, "vri-entry", "fa-signature", "#6f42c1");
                sigNode.setNodeCategory("dss-vri-entry");
                if (sigVri instanceof COSDictionary sigVriDict) {
                    cosBuilder.attachCosChildren(sigNode, sigVriDict,
                            "dss-vri-" + sigHash + "-cos", ctx, 0);
                }
                vriNode.addChild(sigNode);
            }
            dss.addChild(vriNode);
        }

        // Inline remaining COS entries, excluding keys already decoded above
        Set<String> dssExcludeKeys = Set.of("Certs", "OCSPs", "CRLs", "VRI", "Type");
        cosBuilder.attachCosChildren(dss, dssDict, "dss-cos", ctx, 0, dssExcludeKeys);
        return new DssResult(dss, serialToBadge);
    }

    private String badgeColor(String badge) {
        try {
            int idx = (Integer.parseInt(badge) - 1) % BADGE_COLORS.length;
            return BADGE_COLORS[idx];
        } catch (NumberFormatException e) {
            return BADGE_COLORS[0];
        }
    }

    /** Depth-first traversal to collect certs in tree order (for badge numbering). */
    private void collectTreeOrder(List<DssCert> parents, List<DssCert> allCerts,
                                   java.util.Set<String> placed, List<DssCert> result) {
        for (DssCert parent : parents) {
            String key = certDedupeKey(parent);
            if (placed.contains(key)) continue;
            placed.add(key);
            result.add(parent);
            // Find all children: certs whose issuer matches this cert's subject
            List<DssCert> children = new ArrayList<>();
            String parentSubject = parent.cert().getSubjectX500Principal().getName();
            for (DssCert dc : allCerts) {
                if (placed.contains(certDedupeKey(dc))) continue;
                if (dc.cert().getIssuerX500Principal().getName().equals(parentSubject)) {
                    children.add(dc);
                }
            }
            if (!children.isEmpty()) {
                collectTreeOrder(children, allCerts, placed, result);
            }
        }
    }

    /** Recursively build a PdfNode tree: parent cert → child certs (whose issuer matches parent's subject). */
    private PdfNode buildCertTreeNode(DssCert dc, List<DssCert> allCerts,
                                       Map<String, String> serialToBadge, Map<String, List<Integer>> dupeIndices,
                                       java.util.Set<String> visited, CosNodeBuilder.ParseContext ctx) {
        visited.add(certDedupeKey(dc));
        PdfNode node = buildDssCertNode(dc, serialToBadge, dupeIndices, ctx);

        // Find children: certs whose issuer DN matches this cert's subject DN
        String subjectDn = dc.cert().getSubjectX500Principal().getName();
        for (DssCert child : allCerts) {
            if (visited.contains(certDedupeKey(child))) continue;
            if (child.cert().getIssuerX500Principal().getName().equals(subjectDn)) {
                PdfNode childNode = buildCertTreeNode(child, allCerts, serialToBadge, dupeIndices, visited, ctx);
                node.getChildren().add(0, childNode); // insert before COS children
            }
        }
        return node;
    }

    private String certDedupeKey(DssCert dc) {
        return dc.cert().getSerialNumber().toString(16).toUpperCase()
                + "|" + dc.cert().getIssuerX500Principal().getName();
    }

    private PdfNode buildDssCertNode(DssCert dc, Map<String, String> serialToBadge,
                                      Map<String, List<Integer>> dupeIndices, CosNodeBuilder.ParseContext ctx) {
        int index = dc.arrayIndex();
        String nodeId = "dss-cert-" + index;
        X509Certificate cert = dc.cert();
        String cn = extractCN(cert.getSubjectX500Principal().getName());
        String label = cn != null ? cn : cert.getSubjectX500Principal().getName();
        boolean selfSigned = cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());

        // Determine chain role from BasicConstraints extension
        String role;
        if (selfSigned) {
            role = "Root CA";
        } else {
            try {
                role = cert.getBasicConstraints() >= 0 ? "Intermediate CA" : "End Entity";
            } catch (Exception e) {
                role = "Certificate";
            }
        }

        PdfNode node = new PdfNode(nodeId, role + ": " + label, "cert", "fa-certificate",
                selfSigned ? "#ffc107" : "#28a745");
        node.setNodeCategory("dss-cert");

        // Assign badge
        String serial = cert.getSerialNumber().toString(16).toUpperCase();
        String badge = serialToBadge.get(serial);
        if (badge != null) {
            node.setBadge(badge);
            node.setBadgeColor(badgeColor(badge));
        }

        node.addProperty("Role", role);
        node.addProperty("Subject", cert.getSubjectX500Principal().getName());
        node.addProperty("Issuer", cert.getIssuerX500Principal().getName());
        node.addProperty("Serial Number", serial);
        synchronized (DATE_FMT) {
            node.addProperty("Valid From", DATE_FMT.format(cert.getNotBefore()));
            node.addProperty("Valid Until", DATE_FMT.format(cert.getNotAfter()));
        }
        node.addProperty("Signature Algorithm", cert.getSigAlgName());
        node.addProperty("Public Key Algorithm", cert.getPublicKey().getAlgorithm()
                + " (" + getKeyBitLength(cert) + " bit)");
        node.addProperty("Self-Signed", String.valueOf(selfSigned));
        int bc = cert.getBasicConstraints();
        if (bc >= 0) {
            node.addProperty("Basic Constraints", "CA=true" + (bc == Integer.MAX_VALUE ? "" : ", pathLen=" + bc));
        } else {
            node.addProperty("Basic Constraints", "not a CA (end entity)");
        }
        node.addProperty("Version", "V" + cert.getVersion());
        node.addProperty("DER Size", dc.derBytes().length + " bytes");

        // Show how many copies of this cert exist in the PDF array, with all indices
        String key = certDedupeKey(dc);
        List<Integer> indices = dupeIndices.getOrDefault(key, List.of(index));
        if (indices.size() == 1) {
            node.addProperty("Copies in PDF Array", "1 (unique)");
            node.addProperty("PDF Array Index", String.valueOf(indices.get(0)));
        } else {
            node.addProperty("Copies in PDF Array", indices.size() + " identical entries (showing once)");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < indices.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(indices.get(i));
            }
            node.addProperty("PDF Array Indices", sb.toString());
        }

        attachStreamCosInfo(node, dc.cosObj(), nodeId, ctx);
        return node;
    }

    private PdfNode buildUnreadableCertNode(COSBase cosObj, int index, CosNodeBuilder.ParseContext ctx) {
        String nodeId = "dss-cert-" + index;
        byte[] data = readStreamBytes(cosObj);
        PdfNode node;
        if (data != null) {
            node = new PdfNode(nodeId, "Certificate #" + (index + 1) + " (decode error)",
                    "cert", "fa-certificate", "#ffc107");
            node.addProperty("DER Size", data.length + " bytes");
            node.addProperty("Hex Preview", bytesToHex(data, Math.min(data.length, 64)));
        } else {
            node = new PdfNode(nodeId, "Certificate #" + (index + 1) + " (unreadable)",
                    "cert", "fa-certificate", "#dc3545");
        }
        node.setNodeCategory("dss-cert");
        attachStreamCosInfo(node, cosObj, nodeId, ctx);
        return node;
    }

    private PdfNode buildDssOcspNode(COSBase cosObj, int index,
                                      Map<String, String> serialToBadge, CosNodeBuilder.ParseContext ctx) {
        String nodeId = "dss-ocsp-" + index;
        byte[] data = readStreamBytes(cosObj);
        if (data == null) {
            PdfNode node = new PdfNode(nodeId, "OCSP Response #" + (index + 1) + " (unreadable)",
                    "ocsp", "fa-check-circle", "#dc3545");
            node.setNodeCategory("dss-ocsp");
            return node;
        }

        try {
            OCSPResp ocspResp = new OCSPResp(data);
            String statusLabel = ocspStatusText(ocspResp.getStatus());
            PdfNode node = new PdfNode(nodeId, "OCSP Response #" + (index + 1) + " (" + statusLabel + ")",
                    "ocsp", "fa-check-circle", ocspResp.getStatus() == 0 ? "#28a745" : "#ffc107");
            node.setNodeCategory("dss-ocsp");
            node.addProperty("Response Status", statusLabel + " (" + ocspResp.getStatus() + ")");
            node.addProperty("DER Size", data.length + " bytes");

            if (ocspResp.getStatus() == OCSPResp.SUCCESSFUL) {
                BasicOCSPResp basic = (BasicOCSPResp) ocspResp.getResponseObject();
                synchronized (DATE_FMT) {
                    node.addProperty("Produced At", DATE_FMT.format(basic.getProducedAt()));
                }
                node.addProperty("Signature Algorithm", basic.getSignatureAlgOID().getId());
                SingleResp[] responses = basic.getResponses();
                node.addProperty("Single Responses", String.valueOf(responses.length));

                // Collect all matching badges from SingleResp serial numbers
                List<String> matchedBadges = new ArrayList<>();
                for (int r = 0; r < responses.length; r++) {
                    SingleResp sr = responses[r];
                    String srSerial = sr.getCertID().getSerialNumber().toString(16).toUpperCase();
                    PdfNode srNode = new PdfNode(nodeId + "-resp-" + r,
                            "Certificate Status: " + (sr.getCertStatus() == null ? "GOOD" : sr.getCertStatus().toString()),
                            "ocsp-single", "fa-check", sr.getCertStatus() == null ? "#28a745" : "#dc3545");
                    srNode.setNodeCategory("dss-ocsp-single");
                    srNode.addProperty("Serial Number", srSerial);
                    srNode.addProperty("Status",
                            sr.getCertStatus() == null ? "GOOD" : sr.getCertStatus().toString());
                    synchronized (DATE_FMT) {
                        srNode.addProperty("This Update", DATE_FMT.format(sr.getThisUpdate()));
                        if (sr.getNextUpdate() != null)
                            srNode.addProperty("Next Update", DATE_FMT.format(sr.getNextUpdate()));
                    }
                    // Badge: match this OCSP single-response to the cert it validates
                    String badge = serialToBadge.get(srSerial);
                    if (badge != null) {
                        srNode.setBadge(badge);
                        srNode.setBadgeColor(badgeColor(badge));
                        srNode.addProperty("Validates Certificate", "#" + badge);
                        if (!matchedBadges.contains(badge)) matchedBadges.add(badge);
                    }
                    node.addChild(srNode);
                }
                // Set parent OCSP node badge to first match (or comma-joined if multiple)
                if (!matchedBadges.isEmpty()) {
                    String combined = String.join(",", matchedBadges);
                    node.setBadge(combined);
                    node.setBadgeColor(badgeColor(matchedBadges.get(0)));
                }
            }
            attachStreamCosInfo(node, cosObj, nodeId, ctx);
            return node;
        } catch (Exception e) {
            log.debug("Could not decode OCSP response #{}", index, e);
            PdfNode node = new PdfNode(nodeId, "OCSP Response #" + (index + 1) + " (decode error)",
                    "ocsp", "fa-check-circle", "#ffc107");
            node.setNodeCategory("dss-ocsp");
            node.addProperty("Error", e.getMessage());
            node.addProperty("DER Size", data.length + " bytes");
            node.addProperty("Hex Preview", bytesToHex(data, Math.min(data.length, 64)));
            attachStreamCosInfo(node, cosObj, nodeId, ctx);
            return node;
        }
    }

    private PdfNode buildDssCrlNode(COSBase cosObj, int index,
                                     Map<String, String> subjectDnToBadge, CosNodeBuilder.ParseContext ctx) {
        String nodeId = "dss-crl-" + index;
        byte[] data = readStreamBytes(cosObj);
        if (data == null) {
            PdfNode node = new PdfNode(nodeId, "CRL #" + (index + 1) + " (unreadable)",
                    "crl", "fa-ban", "#dc3545");
            node.setNodeCategory("dss-crl");
            return node;
        }

        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(new ByteArrayInputStream(data));
            String issuerDn = crl.getIssuerX500Principal().getName();
            String issuerCN = extractCN(issuerDn);
            String label = "CRL from " + (issuerCN != null ? issuerCN : issuerDn);

            PdfNode node = new PdfNode(nodeId, label, "crl", "fa-ban", "#dc3545");
            node.setNodeCategory("dss-crl");

            // Badge: CRL issuer DN matches a cert's subject DN (issuer published this CRL)
            String badge = subjectDnToBadge.get(issuerDn);
            if (badge != null) {
                node.setBadge(badge);
                node.setBadgeColor(badgeColor(badge));
                node.addProperty("Published By Certificate", "#" + badge);
            }

            node.addProperty("Issuer", issuerDn);
            synchronized (DATE_FMT) {
                node.addProperty("This Update", DATE_FMT.format(crl.getThisUpdate()));
                if (crl.getNextUpdate() != null)
                    node.addProperty("Next Update", DATE_FMT.format(crl.getNextUpdate()));
            }
            node.addProperty("Signature Algorithm", crl.getSigAlgName());
            int revokedCount = crl.getRevokedCertificates() != null ? crl.getRevokedCertificates().size() : 0;
            node.addProperty("Revoked Certificates", String.valueOf(revokedCount));
            node.addProperty("Version", String.valueOf(crl.getVersion()));
            node.addProperty("DER Size", data.length + " bytes");

            if (revokedCount > 0 && revokedCount <= 100) {
                PdfNode revokedNode = new PdfNode(nodeId + "-revoked",
                        "Revoked (" + revokedCount + ")", "folder", "fa-times-circle", "#dc3545");
                revokedNode.setNodeCategory("dss-crl-revoked");
                int ri = 0;
                for (X509CRLEntry entry : crl.getRevokedCertificates()) {
                    PdfNode rNode = new PdfNode(nodeId + "-rev-" + ri,
                            "Serial " + entry.getSerialNumber().toString(16).toUpperCase(),
                            "crl-entry", "fa-times", "#dc3545");
                    rNode.setNodeCategory("dss-crl-entry");
                    synchronized (DATE_FMT) {
                        rNode.addProperty("Revocation Date", DATE_FMT.format(entry.getRevocationDate()));
                    }
                    if (entry.getRevocationReason() != null)
                        rNode.addProperty("Reason", entry.getRevocationReason().name());
                    revokedNode.addChild(rNode);
                    ri++;
                }
                node.addChild(revokedNode);
            } else if (revokedCount > 100) {
                node.addProperty("Note", "Too many revoked entries to list individually (" + revokedCount + ")");
            }
            attachStreamCosInfo(node, cosObj, nodeId, ctx);
            return node;
        } catch (Exception e) {
            log.debug("Could not decode CRL #{}", index, e);
            PdfNode node = new PdfNode(nodeId, "CRL #" + (index + 1) + " (decode error)",
                    "crl", "fa-ban", "#ffc107");
            node.setNodeCategory("dss-crl");
            node.addProperty("Error", e.getMessage());
            node.addProperty("DER Size", data.length + " bytes");
            node.addProperty("Hex Preview", bytesToHex(data, Math.min(data.length, 64)));
            attachStreamCosInfo(node, cosObj, nodeId, ctx);
            return node;
        }
    }

    // ── DSS helpers ───────────────────────────────────────────────────────

    private byte[] readStreamBytes(COSBase cosObj) {
        try {
            COSBase resolved = cosObj;
            if (resolved instanceof org.apache.pdfbox.cos.COSObject)
                resolved = ((org.apache.pdfbox.cos.COSObject) resolved).getObject();
            if (resolved instanceof COSStream stream) {
                try (InputStream is = stream.createInputStream()) {
                    return is.readAllBytes();
                }
            }
        } catch (Exception e) {
            log.debug("Could not read stream bytes", e);
        }
        return null;
    }

    private void attachStreamCosInfo(PdfNode node, COSBase cosObj, String idPrefix,
                                      CosNodeBuilder.ParseContext ctx) {
        try {
            COSBase resolved = cosObj;
            if (resolved instanceof org.apache.pdfbox.cos.COSObject)
                resolved = ((org.apache.pdfbox.cos.COSObject) resolved).getObject();
            if (resolved instanceof COSStream stream) {
                COSObjectKey key = null;
                try {
                    key = stream.getKey();
                } catch (Exception e) {
                    log.trace("stream.getKey() threw, falling back to document index", e);
                }
                if (key == null) key = cosBuilder.findObjectKeyInDocument(stream, ctx.doc);
                if (key != null) {
                    node.setObjectNumber((int) key.getNumber());
                    node.setGenerationNumber(key.getGeneration());
                    node.setCosType("COSStream");
                }
                cosBuilder.attachCosChildren(node, stream, idPrefix + "-cos", ctx, 0);
            }
        } catch (Exception e) {
            log.debug("Error attaching COS info for {}", idPrefix, e);
        }
    }

    private String extractCN(String dn) {
        if (dn == null) return null;
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=") || trimmed.startsWith("cn=")) {
                return trimmed.substring(3).trim();
            }
        }
        return null;
    }

    private int getKeyBitLength(X509Certificate cert) {
        try {
            java.security.interfaces.RSAPublicKey rsa =
                    (java.security.interfaces.RSAPublicKey) cert.getPublicKey();
            return rsa.getModulus().bitLength();
        } catch (ClassCastException ignored) {}
        try {
            java.security.interfaces.ECPublicKey ec =
                    (java.security.interfaces.ECPublicKey) cert.getPublicKey();
            return ec.getParams().getOrder().bitLength();
        } catch (ClassCastException ignored) {}
        return cert.getPublicKey().getEncoded() != null ? cert.getPublicKey().getEncoded().length * 8 : 0;
    }

    private String ocspStatusText(int status) {
        switch (status) {
            case 0: return "successful";
            case 1: return "malformedRequest";
            case 2: return "internalError";
            case 3: return "tryLater";
            case 5: return "sigRequired";
            case 6: return "unauthorized";
            default: return "unknown(" + status + ")";
        }
    }

    private String bytesToHex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", data[i]));
            if ((i + 1) % 32 == 0) sb.append("\n");
        }
        if (length < data.length) sb.append("...");
        return sb.toString();
    }
}
