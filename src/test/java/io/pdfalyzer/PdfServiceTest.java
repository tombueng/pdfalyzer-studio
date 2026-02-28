package io.pdfalyzer;

import io.pdfalyzer.model.PdfNode;
import io.pdfalyzer.model.PdfSession;
import io.pdfalyzer.service.PdfService;
import io.pdfalyzer.service.PdfStructureParser;
import io.pdfalyzer.service.SessionService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class PdfServiceTest {

    private PdfService pdfService;

    @BeforeEach
    void setUp() {
        SessionService sessionService = new SessionService();
        PdfStructureParser parser = new PdfStructureParser();
        pdfService = new PdfService(sessionService, parser);
    }

    @Test
    void parseEmptyBytes() {
        assertThrows(IOException.class, () -> pdfService.uploadAndParse("empty.pdf", new byte[0]));
    }

    @Test
    void parseValidPdf() throws IOException {
        byte[] pdfBytes = createSimplePdf(3);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdfBytes);

        assertNotNull(session.getId());
        assertEquals("test.pdf", session.getFilename());
        assertEquals(3, session.getPageCount());
        assertNotNull(session.getTreeRoot());
        assertEquals("Document Catalog", session.getTreeRoot().getName());
    }

    @Test
    void treeContainsPagesNode() throws IOException {
        byte[] pdfBytes = createSimplePdf(2);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdfBytes);
        PdfNode root = session.getTreeRoot();

        // Find pages node
        PdfNode pagesNode = null;
        for (PdfNode child : root.getChildren()) {
            if ("pages".equals(child.getNodeCategory())) {
                pagesNode = child;
                break;
            }
        }
        assertNotNull(pagesNode, "Should have a pages node");
        assertEquals(2, pagesNode.getChildren().size());
    }

    @Test
    void searchTree() throws IOException {
        byte[] pdfBytes = createSimplePdf(3);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdfBytes);

        java.util.List<PdfNode> results = pdfService.searchTree(session.getId(), "Page 2");
        assertFalse(results.isEmpty(), "Should find Page 2 in tree");
    }

    @Test
    void sessionRetrieval() throws IOException {
        byte[] pdfBytes = createSimplePdf(1);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdfBytes);

        byte[] retrieved = pdfService.getSessionPdfBytes(session.getId());
        assertArrayEquals(pdfBytes, retrieved);
    }

    private byte[] createSimplePdf(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
