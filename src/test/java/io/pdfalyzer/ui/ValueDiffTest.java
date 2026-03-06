package io.pdfalyzer.ui;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the recursive LCS-based value diff algorithm.
 * This is a pure Java port of the diff functions from app-tabs-changes.js,
 * validating the algorithm behaviour for 10 representative cases.
 */
@Slf4j
public class ValueDiffTest {

    private static final int DIFF_MIN_MATCH = 2;

    @Data
    @AllArgsConstructor
    static class Segment {
        String type; // "context", "added", "removed"
        String text;
    }

    @Data
    @AllArgsConstructor
    static class LCSMatch {
        int aIdx;
        int bIdx;
        int length;
    }

    /** Find longest common substring between a[aS..aE) and b[bS..bE). */
    static LCSMatch findLCS(String a, int aS, int aE, String b, int bS, int bE) {
        int bestLen = 0, bestA = 0, bestB = 0;
        for (int i = aS; i < aE; i++) {
            for (int j = bS; j < bE; j++) {
                if (a.charAt(i) != b.charAt(j)) continue;
                int len = 0;
                while (i + len < aE && j + len < bE && a.charAt(i + len) == b.charAt(j + len)) len++;
                if (len > bestLen) { bestLen = len; bestA = i; bestB = j; }
            }
        }
        if (bestLen < DIFF_MIN_MATCH) return null;
        return new LCSMatch(bestA, bestB, bestLen);
    }

    /** Recursively diff a[aS..aE) vs b[bS..bE), appending segments. */
    static void diffRecurse(String a, int aS, int aE, String b, int bS, int bE, List<Segment> segs) {
        if (aS >= aE && bS >= bE) return;
        if (aS >= aE) { segs.add(new Segment("added", b.substring(bS, bE))); return; }
        if (bS >= bE) { segs.add(new Segment("removed", a.substring(aS, aE))); return; }
        LCSMatch m = findLCS(a, aS, aE, b, bS, bE);
        if (m == null) {
            segs.add(new Segment("removed", a.substring(aS, aE)));
            segs.add(new Segment("added", b.substring(bS, bE)));
            return;
        }
        diffRecurse(a, aS, m.aIdx, b, bS, m.bIdx, segs);
        segs.add(new Segment("context", a.substring(m.aIdx, m.aIdx + m.length)));
        diffRecurse(a, m.aIdx + m.length, aE, b, m.bIdx + m.length, bE, segs);
    }

    /** Merge adjacent segments of the same type. */
    static List<Segment> mergeSegments(List<Segment> segs) {
        List<Segment> merged = new ArrayList<>();
        for (Segment seg : segs) {
            if (!merged.isEmpty() && merged.getLast().type.equals(seg.type)) {
                merged.getLast().text += seg.text;
            } else {
                merged.add(new Segment(seg.type, seg.text));
            }
        }
        return merged;
    }

    static List<Segment> computeDiff(String a, String b) {
        if (a.equals(b)) return List.of(new Segment("context", a));
        List<Segment> segs = new ArrayList<>();
        diffRecurse(a, 0, a.length(), b, 0, b.length(), segs);
        return mergeSegments(segs);
    }

    static boolean hasContext(List<Segment> segs) {
        return segs.stream().anyMatch(s -> "context".equals(s.type));
    }

    static boolean hasRemoved(List<Segment> segs) {
        return segs.stream().anyMatch(s -> "removed".equals(s.type));
    }

    static String contextText(List<Segment> segs) {
        return segs.stream().filter(s -> "context".equals(s.type)).map(s -> s.text).reduce("", String::concat);
    }

    static String removedText(List<Segment> segs) {
        return segs.stream().filter(s -> "removed".equals(s.type)).map(s -> s.text).reduce("", String::concat);
    }

    static String addedText(List<Segment> segs) {
        return segs.stream().filter(s -> "added".equals(s.type)).map(s -> s.text).reduce("", String::concat);
    }

    // ─── Test 1: Identical strings → single context segment ───────────────
    @Test
    void identicalStrings() {
        var segs = computeDiff("Hello World", "Hello World");
        assertEquals(1, segs.size());
        assertEquals("context", segs.getFirst().type);
        assertEquals("Hello World", segs.getFirst().text);
        log.info("Test 1 PASS: identical → {}", segs);
    }

    // ─── Test 2: Simple append → context + added ──────────────────────────
    @Test
    void simpleAppend() {
        var segs = computeDiff("Hello", "Hello World");
        assertTrue(hasContext(segs));
        assertFalse(hasRemoved(segs));
        assertEquals("Hello", contextText(segs));
        assertEquals(" World", addedText(segs));
        log.info("Test 2 PASS: append → {}", segs);
    }

    // ─── Test 3: Simple prepend → added + context ─────────────────────────
    @Test
    void simplePrepend() {
        var segs = computeDiff("World", "Hello World");
        assertTrue(hasContext(segs));
        assertFalse(hasRemoved(segs));
        assertEquals("World", contextText(segs));
        assertEquals("Hello ", addedText(segs));
        log.info("Test 3 PASS: prepend → {}", segs);
    }

    // ─── Test 4: Additions on both sides — old fully contained in new ─────
    @Test
    void additionsBothSides() {
        var segs = computeDiff("Invalid input [ERR]", "Invalid aaa input [ERR]aaa");
        assertTrue(hasContext(segs), "should have context segments");
        assertFalse(hasRemoved(segs), "nothing was removed, only additions");
        assertEquals("Invalid input [ERR]", contextText(segs), "all old text should be context");
        log.info("Test 4 PASS: additions both sides → {}", segs);
    }

    // ─── Test 5: Simple deletion from end → context + removed ─────────────
    @Test
    void simpleDeletion() {
        var segs = computeDiff("Hello World", "Hello");
        assertTrue(hasContext(segs));
        assertEquals("Hello", contextText(segs));
        assertEquals(" World", removedText(segs));
        log.info("Test 5 PASS: deletion → {}", segs);
    }

    // ─── Test 6: Total replacement — no shared substring ≥2 chars ─────────
    @Test
    void totalReplacement() {
        var segs = computeDiff("abc", "xyz");
        assertFalse(hasContext(segs), "total replacement should have no context");
        assertEquals("abc", removedText(segs));
        assertEquals("xyz", addedText(segs));
        log.info("Test 6 PASS: total replacement → {}", segs);
    }

    // ─── Test 7: Middle insertion — shared prefix and suffix ──────────────
    @Test
    void middleInsertion() {
        var segs = computeDiff("Valid entry [OK]", "Valid entry EXTRA [OK]");
        assertTrue(hasContext(segs));
        assertFalse(hasRemoved(segs), "nothing was removed");
        assertTrue(contextText(segs).contains("Valid entry"), "prefix in context");
        assertTrue(contextText(segs).contains("[OK]"), "suffix in context");
        log.info("Test 7 PASS: middle insertion → {}", segs);
    }

    // ─── Test 8: Middle deletion ──────────────────────────────────────────
    @Test
    void middleDeletion() {
        var segs = computeDiff("Hello beautiful World", "Hello World");
        assertTrue(hasContext(segs));
        assertTrue(removedText(segs).contains("beautiful"), "removed part");
        assertTrue(contextText(segs).contains("Hello"), "Hello in context");
        assertTrue(contextText(segs).contains("World"), "World in context");
        log.info("Test 8 PASS: middle deletion → {}", segs);
    }

    // ─── Test 9: Empty to non-empty → single added segment ───────────────
    @Test
    void emptyToValue() {
        var segs = computeDiff("", "New value");
        assertEquals(1, segs.size());
        assertEquals("added", segs.getFirst().type);
        assertEquals("New value", segs.getFirst().text);
        log.info("Test 9 PASS: empty→value → {}", segs);
    }

    // ─── Test 10: Non-empty to empty → single removed segment ────────────
    @Test
    void valueToEmpty() {
        var segs = computeDiff("Old value", "");
        assertEquals(1, segs.size());
        assertEquals("removed", segs.getFirst().type);
        assertEquals("Old value", segs.getFirst().text);
        log.info("Test 10 PASS: value→empty → {}", segs);
    }
}
