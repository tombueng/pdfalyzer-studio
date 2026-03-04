package io.pdfalyzer.tools;

import io.pdfalyzer.model.FontDiagnostics;
import io.pdfalyzer.service.FontCollectionHelper;
import io.pdfalyzer.service.FontDiagnosticsBuilder;
import io.pdfalyzer.service.FontFileHelper;
import io.pdfalyzer.service.FontInspectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedPdfMissingGlyphsTest {

    @TempDir
    Path tempDir;

    FontInspectorService service;

    @BeforeEach
    void setUp() {
        service = new FontInspectorService(new FontCollectionHelper(), new FontDiagnosticsBuilder(), new FontFileHelper());
    }

    @Test
    void generatedPdfContainsAtLeastOneFontWithMissingUsedMappings() throws Exception {
        TestPdfGenerator.main(new String[]{tempDir.toString()});

        Path generatedPdf = tempDir.resolve("test.pdf");
        assertTrue(Files.exists(generatedPdf), "Expected generated test.pdf");

        byte[] bytes = Files.readAllBytes(generatedPdf);
        FontDiagnostics diagnostics = service.analyzeFontIssues(bytes);

        assertNotNull(diagnostics);
        assertNotNull(diagnostics.getFonts());
        assertFalse(diagnostics.getFonts().isEmpty(), "Expected at least one font in generated PDF");

        boolean hasMissingUsedMappings = diagnostics.getFonts().stream()
            .anyMatch(f -> f.getUnmappedUsedCodes() > 0 && f.getUnencodableUsedChars() > 0);

        String fontSummary = diagnostics.getFonts().stream()
            .map(f -> String.format(
                "%s [type=%s, glyphs=%d, distinctUsed=%d, mapped=%d, unmapped=%d, missing=%d]",
                f.getFontName(),
                f.getFontType(),
                f.getGlyphCount(),
                f.getDistinctUsedCodes(),
                f.getMappedUsedCodes(),
                f.getUnmappedUsedCodes(),
                f.getUnencodableUsedChars()
            ))
            .collect(Collectors.joining(System.lineSeparator()));

        assertTrue(
            hasMissingUsedMappings,
            "Expected at least one font with unmapped used codes and missing glyph chars in generated PDF.\n"
                + "Diagnostics:\n"
                + fontSummary
        );
    }
}
