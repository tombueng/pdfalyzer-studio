package io.pdfalyzer;

import io.pdfalyzer.model.PdfNode;
import io.pdfalyzer.model.PdfSession;
import io.pdfalyzer.service.PdfService;
import io.pdfalyzer.service.PdfStructureParser;
import io.pdfalyzer.service.SessionService;
import io.pdfalyzer.service.CosEditService;
import io.pdfalyzer.model.CosUpdateRequest;
import io.pdfalyzer.web.ApiController;
import io.pdfalyzer.service.FontInspectorService;
import io.pdfalyzer.service.ValidationService;
import io.pdfalyzer.service.PdfEditService;
import org.springframework.http.ResponseEntity;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.AlphaComposite;
import javax.imageio.ImageIO;

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

    @Test
    void addAndRemoveDictionaryEntry() throws IOException {
        byte[] pdfBytes = createSimplePdf(1);
        PdfSession session = pdfService.uploadAndParse("test.pdf", pdfBytes);
        CosEditService cosService = new CosEditService();
        // look for a dictionary node that belongs to an indirect object
        PdfNode dictNode = null;
        LinkedList<PdfNode> queue = new LinkedList<>();
        queue.add(session.getTreeRoot());
        while (!queue.isEmpty() && dictNode == null) {
            PdfNode n = queue.removeFirst();
            if ("COSDictionary".equals(n.getCosType()) && n.getObjectNumber() >= 0) {
                dictNode = n;
                break;
            }
            queue.addAll(n.getChildren());
        }
        assertNotNull(dictNode, "expected at least one dictionary object in PDF");
        CosUpdateRequest req = new CosUpdateRequest();
        req.setObjectNumber(dictNode.getObjectNumber());
        req.setGenerationNumber(dictNode.getGenerationNumber());
        req.setKeyPath(Arrays.asList("MyTestKey"));
        req.setNewValue("1234");
        req.setValueType("COSInteger");
        req.setOperation("add");
        byte[] modified = cosService.updateCosValue(pdfBytes, req);
        session = pdfService.uploadAndParse("test2.pdf", modified);
        List<PdfNode> results = pdfService.searchTree(session.getId(), "MyTestKey");
        assertFalse(results.isEmpty());
        // remove it
        req.setOperation("remove");
        byte[] modified2 = cosService.updateCosValue(modified, req);
        session = pdfService.uploadAndParse("test3.pdf", modified2);
        results = pdfService.searchTree(session.getId(), "MyTestKey");
        assertTrue(results.isEmpty());
    }

    @Test
    void resourceEndpointReturnsImage() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            BufferedImage bi = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img =
                    org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, bi);
            // explicitly register image as XObject so parser will see it
            PDResources res = page.getResources();
            if (res == null) {
                res = new PDResources();
                page.setResources(res);
            }
            res.add(img, "Im1");
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.drawImage(img, 100, 100);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        PdfSession session = pdfService.uploadAndParse("img.pdf", pdfBytes);
        // locate the first COSStream in the semantic tree whose /Subtype entry equals "Image"
        int objNum = -1, genNum = -1;
        LinkedList<PdfNode> queue2 = new LinkedList<>();
        queue2.add(session.getTreeRoot());
        while (!queue2.isEmpty() && objNum < 0) {
            PdfNode n = queue2.removeFirst();
            if ("COSStream".equals(n.getCosType()) && n.getObjectNumber() >= 0) {
                // look for subtype child
                for (PdfNode child : n.getChildren()) {
                    if ("/Subtype".equals(child.getName()) && "Image".equals(child.getRawValue())) {
                        objNum = n.getObjectNumber();
                        genNum = n.getGenerationNumber();
                        break;
                    }
                }
                if (objNum >= 0) break;
            }
            queue2.addAll(n.getChildren());
        }
        // If tree search failed, fall back to scanning COSDocument directly
        if (objNum < 0) {
            try (PDDocument doc2 = Loader.loadPDF(pdfBytes)) {
                COSDocument cosDoc = doc2.getDocument();
                for (COSObjectKey key : cosDoc.getXrefTable().keySet()) {
                    COSObject cosObj = cosDoc.getObjectFromPool(key);
                    if (cosObj != null && cosObj.getObject() instanceof COSStream) {
                        COSStream stream = (COSStream) cosObj.getObject();
                        COSName subtype = stream.getCOSName(COSName.SUBTYPE);
                        if (COSName.IMAGE.equals(subtype)) {
                            objNum = (int) key.getNumber();
                            genNum = key.getGeneration();
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(objNum >= 0, "expected to find an image stream object in tree or document");
        ApiController ctrl = new ApiController(pdfService,
                new FontInspectorService(), new ValidationService(),
                new PdfEditService(), new CosEditService(), new PdfStructureParser());
        ResponseEntity<byte[]> resp = ctrl.getResource(session.getId(),
                objNum, genNum, null, true);
        assertNotNull(resp.getHeaders().getContentType());
        byte[] body = resp.getBody();
        assertTrue(body.length > 0);
        // basic header check for PNG/JPEG
        assertTrue(body.length > 8);
        assertTrue((body[0] & 0xFF) == 0x89 || (body[0] & 0xFF) == 0xFF);
    }

    @Test
    void resourceEndpointWithXmlStreamReturnsXml() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // create a stand‑alone COSStream with XML subtype and put some content
            COSStream raw = doc.getDocument().createCOSStream();
            raw.setName(COSName.SUBTYPE, "XML");
            try (java.io.OutputStream os = raw.createOutputStream()) {
                os.write("<root>hello</root>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            // attach to catalog so parser will see it when traversing dictionaries
            COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
            cat.setItem(COSName.getPDFName("MyXML"), raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        PdfSession session = pdfService.uploadAndParse("xml.pdf", pdfBytes);
        // search semantic tree for XML subtype stream
        int objNum = -1, genNum = -1;
        LinkedList<PdfNode> queue = new LinkedList<>();
        queue.add(session.getTreeRoot());
        while (!queue.isEmpty() && objNum < 0) {
            PdfNode n = queue.removeFirst();
            if ("COSStream".equals(n.getCosType()) && n.getObjectNumber() >= 0) {
                for (PdfNode child : n.getChildren()) {
                    if ("/Subtype".equals(child.getName()) && "XML".equalsIgnoreCase(child.getRawValue())) {
                        objNum = n.getObjectNumber();
                        genNum = n.getGenerationNumber();
                        break;
                    }
                }
                if (objNum >= 0) break;
            }
            queue.addAll(n.getChildren());
        }
        // fallback to scanning COS document directly if semantic tree missed it
        if (objNum < 0) {
            try (PDDocument doc2 = Loader.loadPDF(pdfBytes)) {
                COSDocument cosDoc = doc2.getDocument();
                for (COSObjectKey key : cosDoc.getXrefTable().keySet()) {
                    COSObject cosObj = cosDoc.getObjectFromPool(key);
                    if (cosObj != null && cosObj.getObject() instanceof COSStream) {
                        COSStream stream = (COSStream) cosObj.getObject();
                        COSName subtypeName = stream.getCOSName(COSName.SUBTYPE);
                        if (subtypeName != null && "XML".equalsIgnoreCase(subtypeName.getName())) {
                            objNum = (int) key.getNumber();
                            genNum = key.getGeneration();
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(objNum >= 0, "expected xml stream node");

        ApiController ctrl = new ApiController(pdfService,
                new FontInspectorService(), new ValidationService(),
                new PdfEditService(), new CosEditService(), new PdfStructureParser());
        ResponseEntity<byte[]> resp = ctrl.getResource(session.getId(),
                objNum, genNum, null, true);
        assertEquals("application/xml", resp.getHeaders().getContentType().toString());
        byte[] body = resp.getBody();
        assertNotNull(body);
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("<root>"));
    }

    @Test
    void resourceEndpointWithMaskReturnsImage() throws IOException {
        // create PDF with an image and a separate soft mask stream
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // color image with transparency (alpha) -> PDFBox will create SMask
            BufferedImage bi = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = bi.createGraphics();
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, 10, 10);
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(255, 0, 0, 128));
            g.fillOval(2, 2, 6, 6);
            g.dispose();
            PDImageXObject img = LosslessFactory.createFromImage(doc, bi);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.drawImage(img, 50, 50);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        PdfSession session = pdfService.uploadAndParse("imgmask.pdf", pdfBytes);
        // find any stream that is mask (SMask or Mask) by looking for "mask" in name or property
        int maskObj = -1, maskGen = -1;
        LinkedList<PdfNode> queue = new LinkedList<>();
        queue.add(session.getTreeRoot());
        while (!queue.isEmpty() && maskObj < 0) {
            PdfNode n = queue.removeFirst();
            if ("COSStream".equals(n.getCosType()) && n.getName().toLowerCase().contains("mask")
                    && n.getObjectNumber() >= 0) {
                maskObj = n.getObjectNumber();
                maskGen = n.getGenerationNumber();
                break;
            }
            queue.addAll(n.getChildren());
        }
        assertTrue(maskObj >= 0, "should find a mask stream");
        ApiController ctrl = new ApiController(pdfService,
                new FontInspectorService(), new ValidationService(),
                new PdfEditService(), new CosEditService(), new PdfStructureParser());
        ResponseEntity<byte[]> respMask = ctrl.getResource(session.getId(), maskObj, maskGen, null, true);
            assertNotNull(respMask.getHeaders().getContentType());
            byte[] bodyMask = respMask.getBody();
            assertTrue(bodyMask.length > 0);
            // should be valid PNG since we convert via PDImageXObject
            assertEquals((byte)0x89, bodyMask[0]);
        assertEquals((byte)0x89, bodyMask[0]);
    }
}
