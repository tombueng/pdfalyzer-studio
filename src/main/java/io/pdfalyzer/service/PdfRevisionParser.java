package io.pdfalyzer.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import io.pdfalyzer.model.PdfRevision;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses PDF byte streams to identify revision boundaries.
 * Each incremental update appends a new xref/trailer/%%EOF section.
 * This parser finds all %%EOF markers and builds a revision list.
 */
@Service
@Slf4j
public class PdfRevisionParser {

    private static final byte[] EOF_PATTERN = "%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STARTXREF_PATTERN = "startxref".getBytes(StandardCharsets.US_ASCII);

    /**
     * Parse all revisions from PDF bytes.
     * Each revision ends at a %%EOF marker (plus trailing whitespace).
     */
    public List<PdfRevision> parseRevisions(byte[] pdfBytes) {
        List<Long> eofPositions = findEofPositions(pdfBytes);
        if (eofPositions.isEmpty()) {
            log.debug("No %%EOF markers found in PDF");
            return List.of();
        }

        List<PdfRevision> revisions = new ArrayList<>();
        long prevEnd = 0;

        for (int i = 0; i < eofPositions.size(); i++) {
            long eofPos = eofPositions.get(i);
            long endOffset = computeRevisionEnd(pdfBytes, eofPos);
            long startxref = extractStartxrefValue(pdfBytes, eofPos);

            PdfRevision rev = PdfRevision.builder()
                    .revisionIndex(i + 1)
                    .startOffset(prevEnd)
                    .endOffset(endOffset)
                    .eofPosition(eofPos)
                    .startxrefValue(startxref)
                    .size(endOffset - prevEnd)
                    .build();

            revisions.add(rev);
            prevEnd = endOffset;
            log.debug("Revision {}: bytes [{}, {}), startxref={}, size={}",
                    i + 1, rev.getStartOffset(), endOffset, startxref, rev.getSize());
        }

        return revisions;
    }

    /**
     * Determine which revisions a signature's byte range covers.
     * A signature covers a revision if its byte range end >= revision's endOffset.
     */
    public List<PdfRevision> mapSignatureToRevisions(int[] byteRange, List<PdfRevision> revisions) {
        if (byteRange == null || byteRange.length < 4 || revisions.isEmpty()) {
            return List.of();
        }

        // The signature covers from byte 0 to byteRange[2]+byteRange[3]
        long signatureCoverageEnd = (long) byteRange[2] + byteRange[3];

        List<PdfRevision> covered = new ArrayList<>();
        for (PdfRevision rev : revisions) {
            if (rev.getEndOffset() <= signatureCoverageEnd) {
                covered.add(rev);
            }
        }
        return covered;
    }

    /**
     * Find all %%EOF marker positions in the PDF byte stream.
     */
    private List<Long> findEofPositions(byte[] pdfBytes) {
        List<Long> positions = new ArrayList<>();
        for (int i = 0; i <= pdfBytes.length - EOF_PATTERN.length; i++) {
            if (matchesAt(pdfBytes, i, EOF_PATTERN)) {
                positions.add((long) i);
                i += EOF_PATTERN.length - 1; // skip past this match
            }
        }
        return positions;
    }

    /**
     * Compute the end of a revision: position after %%EOF plus any trailing CR/LF.
     */
    private long computeRevisionEnd(byte[] pdfBytes, long eofPos) {
        long pos = eofPos + EOF_PATTERN.length;
        // Skip trailing whitespace (CR, LF, CRLF)
        while (pos < pdfBytes.length) {
            byte b = pdfBytes[(int) pos];
            if (b == '\r' || b == '\n') {
                pos++;
            } else {
                break;
            }
        }
        return pos;
    }

    /**
     * Extract the startxref value that precedes a %%EOF marker.
     * Scans backwards from eofPos to find "startxref" keyword, then parses the number.
     */
    private long extractStartxrefValue(byte[] pdfBytes, long eofPos) {
        // Search backwards from eofPos for "startxref"
        int searchStart = (int) Math.max(0, eofPos - 256);
        int searchEnd = (int) eofPos;

        int startxrefPos = -1;
        for (int i = searchEnd - STARTXREF_PATTERN.length; i >= searchStart; i--) {
            if (matchesAt(pdfBytes, i, STARTXREF_PATTERN)) {
                startxrefPos = i;
                break;
            }
        }

        if (startxrefPos < 0) {
            log.debug("No startxref found before %%EOF at position {}", eofPos);
            return -1;
        }

        // Parse the number after "startxref" (skip whitespace)
        int numStart = startxrefPos + STARTXREF_PATTERN.length;
        while (numStart < searchEnd && isWhitespace(pdfBytes[numStart])) {
            numStart++;
        }

        StringBuilder sb = new StringBuilder();
        while (numStart < searchEnd && pdfBytes[numStart] >= '0' && pdfBytes[numStart] <= '9') {
            sb.append((char) pdfBytes[numStart]);
            numStart++;
        }

        if (sb.isEmpty()) {
            return -1;
        }

        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            log.debug("Failed to parse startxref value: {}", sb);
            return -1;
        }
    }

    private boolean matchesAt(byte[] data, int offset, byte[] pattern) {
        if (offset + pattern.length > data.length) return false;
        for (int j = 0; j < pattern.length; j++) {
            if (data[offset + j] != pattern[j]) return false;
        }
        return true;
    }

    private boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }
}
