package io.pdfalyzer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.PdfNode;
import io.pdfalyzer.model.PdfSession;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PdfService {

    private final SessionService sessionService;
    private final PdfStructureParser structureParser;

    public PdfService(SessionService sessionService, PdfStructureParser structureParser) {
        this.sessionService = sessionService;
        this.structureParser = structureParser;
    }

    public PdfSession uploadAndParse(String filename, byte[] pdfBytes) throws IOException {
        PdfSession session = sessionService.createSession(filename, pdfBytes);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            session.setTreeRoot(structureParser.buildTree(doc));
            session.setPageCount(doc.getNumberOfPages());
        }
        log.info("Parsed '{}': {} pages", filename, session.getPageCount());
        return session;
    }

    public byte[] getSessionPdfBytes(String sessionId) {
        return sessionService.getSession(sessionId).getPdfBytes();
    }

    public PdfNode getTree(String sessionId) {
        return sessionService.getSession(sessionId).getTreeRoot();
    }

    public PdfSession getSession(String sessionId) {
        return sessionService.getSession(sessionId);
    }

    public List<PdfNode> searchTree(String sessionId, String query) {
        PdfNode root = getTree(sessionId);
        List<PdfNode> results = new ArrayList<>();
        searchNodes(root, query.toLowerCase(), results);
        return results;
    }

    private void searchNodes(PdfNode node, String query, List<PdfNode> results) {
        if (node == null)
            return;

        boolean matches = false;
        if (node.getName() != null && node.getName().toLowerCase().contains(query)) {
            matches = true;
        }
        if (!matches && node.getProperties() != null) {
            for (String value : node.getProperties().values()) {
                if (value != null && value.toLowerCase().contains(query)) {
                    matches = true;
                    break;
                }
            }
        }
        if (matches) {
            results.add(node);
        }

        for (PdfNode child : node.getChildren()) {
            searchNodes(child, query, results);
        }
    }

    public void updateSessionPdf(String sessionId, byte[] newBytes) throws IOException {
        PdfSession session = sessionService.getSession(sessionId);
        session.setPdfBytes(newBytes);
        try (PDDocument doc = Loader.loadPDF(newBytes)) {
            session.setTreeRoot(structureParser.buildTree(doc));
            session.setPageCount(doc.getNumberOfPages());
        }
        // Invalidate cached raw COS tree so it's rebuilt on next access
        session.setRawCosTree(null);
    }
}
