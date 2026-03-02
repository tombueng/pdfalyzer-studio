package io.pdfalyzer.service;

import io.pdfalyzer.model.FontDiagnostics;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FontInspectorServiceCampinglisteTest {

    private final FontInspectorService service = new FontInspectorService();

    @Test
    void campingliste_calibri_bold_metrics_are_consistent() throws IOException {
        byte[] pdfBytes = readCampinglistePdf();

        FontDiagnostics diagnostics = service.analyzeFontIssues(pdfBytes);
        assertNotNull(diagnostics);
        assertNotNull(diagnostics.getFonts());
        assertFalse(diagnostics.getFonts().isEmpty(), "Expected fonts in Campingliste.pdf");

        FontDiagnostics.FontDiagnosticsEntry target = diagnostics.getFonts().stream()
            .filter(f -> String.valueOf(f.getFontName()).contains("Calibri-Bold"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected Calibri-Bold font entry in diagnostics"));

        assertTrue(target.getGlyphCount() >= target.getDistinctUsedCodes(),
            "Glyph count should be >= distinct used codes");
        assertEquals(target.getDistinctUsedCodes(), target.getMappedUsedCodes() + target.getUnmappedUsedCodes(),
            "Mapped + unmapped used codes should equal distinct used codes");

        FontDiagnostics.FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
            pdfBytes,
            target.getObjectNumber(),
            target.getGenerationNumber()
        );

        assertNotNull(detail);
        assertNotNull(detail.getGlyphMappings());
        assertNotNull(detail.getMissingUsedGlyphMappings());

        Map<Integer, FontDiagnostics.GlyphMapping> allMappingsByCode = detail.getGlyphMappings().stream()
            .collect(Collectors.toMap(FontDiagnostics.GlyphMapping::getCode, Function.identity(), (a, b) -> a));

        for (FontDiagnostics.GlyphMapping missing : detail.getMissingUsedGlyphMappings()) {
            assertTrue(missing.getUsedCount() > 0, "Missing rows must be used in document");
            assertFalse(missing.isMapped(), "Missing rows must be unmapped");

            FontDiagnostics.GlyphMapping inAll = allMappingsByCode.get(missing.getCode());
            assertNotNull(inAll, "Missing row code should exist in full mapping table");
            assertFalse(inAll.isMapped(), "Missing row code in full mapping table must be unmapped");
        }

        assertEquals(target.getUnmappedUsedCodes(), detail.getMissingUsedGlyphMappings().size(),
            "Missing-used mapping rows should match unmapped used code count");

        if (target.getMappedUsedCodes() == target.getDistinctUsedCodes()) {
            assertEquals(0, target.getUnmappedUsedCodes(), "All mapped means no unmapped used codes");
            assertEquals(0, target.getUnencodableUsedChars(),
                "All mapped used codes should imply no missing glyph chars with current definition");
            assertTrue(detail.getMissingUsedGlyphMappings().isEmpty(),
                "All mapped used codes should imply empty missing table");
        }
    }

    @Test
    void campingliste_all_font_entries_follow_count_invariants() throws IOException {
        byte[] pdfBytes = readCampinglistePdf();
        FontDiagnostics diagnostics = service.analyzeFontIssues(pdfBytes);

        for (FontDiagnostics.FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            assertEquals(entry.getDistinctUsedCodes(), entry.getMappedUsedCodes() + entry.getUnmappedUsedCodes(),
                "Mapped + unmapped used codes must equal distinct used codes for " + entry.getFontName());
            assertTrue(entry.getGlyphCount() >= entry.getDistinctUsedCodes(),
                "Glyph positions must be >= distinct used codes for " + entry.getFontName());
            assertTrue(entry.getUnencodableUsedChars() >= 0,
                "Missing glyph chars must be non-negative for " + entry.getFontName());
        }
    }

    private byte[] readCampinglistePdf() throws IOException {
        ClassPathResource resource = new ClassPathResource("files/Campingliste.pdf");
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToByteArray(in);
        }
    }
}
