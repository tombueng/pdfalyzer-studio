package io.pdfalyzer.service;

import io.pdfalyzer.model.FontDiagnostics;
import io.pdfalyzer.model.FontDiagnostics.DiagnosticStatus;
import io.pdfalyzer.model.FontDiagnostics.ExtractionStatus;
import io.pdfalyzer.model.FontDiagnostics.FontDiagnosticsDetail;
import io.pdfalyzer.model.FontDiagnostics.FontDiagnosticsEntry;
import io.pdfalyzer.model.FontDiagnostics.GlyphMapping;
import io.pdfalyzer.model.FontDiagnostics.RenderStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GlyphMappingDiagnosticStatusTest {

    static FontInspectorService service;
    private static byte[] testPdfBytes;

    @BeforeAll
    static void loadPdfs() throws Exception {
        service = new FontInspectorService(new FontCollectionHelper(), new FontDiagnosticsBuilder(), new FontFileHelper());
        testPdfBytes = Files.readAllBytes(Path.of("src/main/resources/sample-pdfs/test.pdf"));
    }

    @Test
    void generatedPdf_diagnosticStatusIsConsistentWithMappedAndPresent() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(testPdfBytes);
        assertNotNull(diagnostics);
        assertFalse(diagnostics.getFonts().isEmpty());

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                testPdfBytes, entry.getObjectNumber(), entry.getGenerationNumber());

            for (GlyphMapping m : detail.getGlyphMappings()) {
                assertNotNull(m.getDiagnosticStatus(),
                    "Code " + m.getCode() + " in " + entry.getFontName() + " should have a diagnostic status");
                assertNotNull(m.getGlyphName(),
                    "Code " + m.getCode() + " in " + entry.getFontName() + " should have a glyph name");

                if (m.isMapped() && m.isGlyphPresent()) {
                    assertTrue(
                        m.getDiagnosticStatus() == DiagnosticStatus.OK
                            || m.getDiagnosticStatus() == DiagnosticStatus.ENCODING_MISMATCH,
                        "Mapped+present code " + m.getCode() + " in " + entry.getFontName()
                            + " should be OK or ENCODING_MISMATCH, got: " + m.getDiagnosticStatus());
                } else if (!m.isMapped() && m.isGlyphPresent()) {
                    assertEquals(DiagnosticStatus.NO_UNICODE_MAPPING, m.getDiagnosticStatus(),
                        "Unmapped+present code " + m.getCode() + " in " + entry.getFontName()
                            + " should be NO_UNICODE_MAPPING");
                } else if (!m.isGlyphPresent()) {
                    assertEquals(DiagnosticStatus.GLYPH_NOT_IN_FONT, m.getDiagnosticStatus(),
                        "Not-present code " + m.getCode() + " in " + entry.getFontName()
                            + " should be GLYPH_NOT_IN_FONT");
                }
            }
        }
    }

    @Test
    void generatedPdf_hasMultipleDiagnosticStatuses() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(testPdfBytes);

        // The probe font (FreeSans with empty ToUnicode) should have multiple statuses
        FontDiagnosticsEntry probeFont = diagnostics.getFonts().stream()
            .filter(f -> f.getUnmappedUsedCodes() > 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected at least one font with unmapped used codes in test.pdf"));

        FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
            testPdfBytes, probeFont.getObjectNumber(), probeFont.getGenerationNumber());

        Set<DiagnosticStatus> statuses = detail.getGlyphMappings().stream()
            .map(GlyphMapping::getDiagnosticStatus)
            .collect(Collectors.toSet());

        assertTrue(statuses.size() >= 2,
            "Expected at least 2 distinct diagnostic statuses for probe font, got: " + statuses);
        assertTrue(statuses.contains(DiagnosticStatus.NO_UNICODE_MAPPING),
            "Expected NO_UNICODE_MAPPING in probe font statuses: " + statuses);
        assertTrue(statuses.contains(DiagnosticStatus.GLYPH_NOT_IN_FONT),
            "Expected GLYPH_NOT_IN_FONT in probe font statuses: " + statuses);
    }

    @Test
    void generatedPdf_renderAndExtractionStatusesPopulated() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(testPdfBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                testPdfBytes, entry.getObjectNumber(), entry.getGenerationNumber());

            for (GlyphMapping m : detail.getGlyphMappings()) {
                assertNotNull(m.getRenderStatus(),
                    "RenderStatus should not be null for code " + m.getCode()
                        + " in font " + entry.getFontName());
                assertNotNull(m.getExtractionStatus(),
                    "ExtractionStatus should not be null for code " + m.getCode()
                        + " in font " + entry.getFontName());
            }
        }
    }

    @Test
    void generatedPdf_renderStatusConsistentWithEmbeddingAndPresence() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(testPdfBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                testPdfBytes, entry.getObjectNumber(), entry.getGenerationNumber());

            for (GlyphMapping m : detail.getGlyphMappings()) {
                if (!entry.isEmbedded()) {
                    assertEquals(RenderStatus.NOT_EMBEDDED, m.getRenderStatus(),
                        "Non-embedded font code " + m.getCode() + " in " + entry.getFontName()
                            + " should have NOT_EMBEDDED render status");
                } else if (!m.isGlyphPresent()) {
                    assertEquals(RenderStatus.GLYPH_MISSING, m.getRenderStatus(),
                        "Missing glyph code " + m.getCode() + " in " + entry.getFontName()
                            + " should have GLYPH_MISSING render status");
                } else {
                    assertEquals(RenderStatus.OK, m.getRenderStatus(),
                        "Present glyph code " + m.getCode() + " in " + entry.getFontName()
                            + " should have OK render status");
                }
            }
        }
    }

    @Test
    void generatedPdf_extractionStatusConsistentWithMapping() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(testPdfBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                testPdfBytes, entry.getObjectNumber(), entry.getGenerationNumber());

            for (GlyphMapping m : detail.getGlyphMappings()) {
                if (m.isMapped()) {
                    assertTrue(
                        m.getExtractionStatus() == ExtractionStatus.OK
                            || m.getExtractionStatus() == ExtractionStatus.ENCODING_MISMATCH,
                        "Mapped code " + m.getCode() + " in " + entry.getFontName()
                            + " should have OK or ENCODING_MISMATCH extraction status, got: "
                            + m.getExtractionStatus());
                } else {
                    assertEquals(ExtractionStatus.NO_UNICODE_MAPPING, m.getExtractionStatus(),
                        "Unmapped code " + m.getCode() + " in " + entry.getFontName()
                            + " should have NO_UNICODE_MAPPING extraction status");
                }
            }
        }
    }
}
