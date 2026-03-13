package io.pdfalyzer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.pdfalyzer.model.PdfRevision;
import io.pdfalyzer.service.PdfRevisionParser;

import static org.junit.jupiter.api.Assertions.*;

class PdfRevisionParserTest {

    private final PdfRevisionParser parser = new PdfRevisionParser();

    @Test
    void parsesSingleRevision() {
        byte[] pdf = buildPdf("startxref\n100\n%%EOF\n");
        List<PdfRevision> revisions = parser.parseRevisions(pdf);
        assertEquals(1, revisions.size());
        assertEquals(1, revisions.get(0).getRevisionIndex());
        assertEquals(0, revisions.get(0).getStartOffset());
        assertEquals(100, revisions.get(0).getStartxrefValue());
    }

    @Test
    void parsesMultipleRevisions() {
        String rev1 = "startxref\n50\n%%EOF\n";
        String rev2 = "startxref\n200\n%%EOF\n";
        byte[] pdf = (rev1 + rev2).getBytes(StandardCharsets.US_ASCII);

        List<PdfRevision> revisions = parser.parseRevisions(pdf);
        assertEquals(2, revisions.size());
        assertEquals(1, revisions.get(0).getRevisionIndex());
        assertEquals(2, revisions.get(1).getRevisionIndex());
        assertEquals(50, revisions.get(0).getStartxrefValue());
        assertEquals(200, revisions.get(1).getStartxrefValue());
        // Second revision starts where first ends
        assertEquals(revisions.get(0).getEndOffset(), revisions.get(1).getStartOffset());
    }

    @Test
    void handlesEmptyInput() {
        List<PdfRevision> revisions = parser.parseRevisions(new byte[0]);
        assertTrue(revisions.isEmpty());
    }

    @Test
    void handlesNoEofMarker() {
        byte[] pdf = "just some random content".getBytes(StandardCharsets.US_ASCII);
        List<PdfRevision> revisions = parser.parseRevisions(pdf);
        assertTrue(revisions.isEmpty());
    }

    @Test
    void mapsSignatureToRevisions() {
        String rev1 = "startxref\n50\n%%EOF\n";
        String rev2 = "startxref\n200\n%%EOF\n";
        byte[] pdf = (rev1 + rev2).getBytes(StandardCharsets.US_ASCII);

        List<PdfRevision> revisions = parser.parseRevisions(pdf);
        assertEquals(2, revisions.size());

        // Byte range covering only first revision
        int[] byteRangeFirstOnly = {0, 10, 12, (int) (revisions.get(0).getEndOffset() - 12)};
        List<PdfRevision> covered = parser.mapSignatureToRevisions(byteRangeFirstOnly, revisions);
        assertEquals(1, covered.size());
        assertEquals(1, covered.get(0).getRevisionIndex());

        // Byte range covering both revisions
        int[] byteRangeBoth = {0, 10, 12, (int) (revisions.get(1).getEndOffset() - 12)};
        covered = parser.mapSignatureToRevisions(byteRangeBoth, revisions);
        assertEquals(2, covered.size());
    }

    @Test
    void handlesCrLfAfterEof() {
        byte[] pdf = "startxref\n42\n%%EOF\r\n".getBytes(StandardCharsets.US_ASCII);
        List<PdfRevision> revisions = parser.parseRevisions(pdf);
        assertEquals(1, revisions.size());
        assertEquals(pdf.length, revisions.get(0).getEndOffset());
    }

    private byte[] buildPdf(String content) {
        return content.getBytes(StandardCharsets.US_ASCII);
    }
}
