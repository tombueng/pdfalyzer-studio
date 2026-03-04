package io.pdfalyzer.service;

import io.pdfalyzer.model.FontDiagnostics;
import io.pdfalyzer.model.FontDiagnostics.DiagnosticStatus;
import io.pdfalyzer.model.FontDiagnostics.EncodingDiagnostics;
import io.pdfalyzer.model.FontDiagnostics.ExtractionStatus;
import io.pdfalyzer.model.FontDiagnostics.FontDiagnosticsDetail;
import io.pdfalyzer.model.FontDiagnostics.FontDiagnosticsEntry;
import io.pdfalyzer.model.FontDiagnostics.GlyphMapping;
import io.pdfalyzer.model.FontDiagnostics.RenderStatus;
import io.pdfalyzer.tools.TestPdfGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GlyphMappingDiagnosticStatusTest {

    static FontInspectorService service;
    private static byte[] generatedTestPdfBytes;
    private static byte[] campinglisteBytes;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void loadPdfs() throws Exception {
        service = new FontInspectorService(new FontCollectionHelper(), new FontDiagnosticsBuilder(), new FontFileHelper());
        // Generate the comprehensive test.pdf which has a probe font with empty ToUnicode
        TestPdfGenerator.main(new String[]{tempDir.toString()});
        generatedTestPdfBytes = Files.readAllBytes(tempDir.resolve("test.pdf"));

        campinglisteBytes = readResource("files/Campingliste.pdf");
    }

    private static byte[] readResource(String path) throws IOException {
        ClassPathResource r = new ClassPathResource(path);
        try (InputStream in = r.getInputStream()) {
            return StreamUtils.copyToByteArray(in);
        }
    }

    @Test
    void generatedPdf_diagnosticStatusIsConsistentWithMappedAndPresent() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(generatedTestPdfBytes);
        assertNotNull(diagnostics);
        assertFalse(diagnostics.getFonts().isEmpty());

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                generatedTestPdfBytes, entry.getObjectNumber(), entry.getGenerationNumber());

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
        FontDiagnostics diagnostics = service.analyzeFontIssues(generatedTestPdfBytes);

        // The probe font (FreeSans with empty ToUnicode) should have multiple statuses
        FontDiagnosticsEntry probeFont = diagnostics.getFonts().stream()
            .filter(f -> f.getUnmappedUsedCodes() > 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected at least one font with unmapped used codes in generated test.pdf"));

        FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
            generatedTestPdfBytes, probeFont.getObjectNumber(), probeFont.getGenerationNumber());

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
    void campingliste_allMappedUsedCodesAreOk() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(campinglisteBytes);

        FontDiagnosticsEntry calibriBold = diagnostics.getFonts().stream()
            .filter(f -> String.valueOf(f.getFontName()).contains("Calibri-Bold"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected Calibri-Bold in Campingliste.pdf"));

        FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
            campinglisteBytes, calibriBold.getObjectNumber(), calibriBold.getGenerationNumber());

        List<GlyphMapping> mappedUsed = detail.getGlyphMappings().stream()
            .filter(m -> m.isMapped() && m.getUsedCount() > 0)
            .toList();

        assertFalse(mappedUsed.isEmpty(), "Should have mapped+used codes for Calibri-Bold");

        for (GlyphMapping m : mappedUsed) {
            assertTrue(
                m.getDiagnosticStatus() == DiagnosticStatus.OK
                    || m.getDiagnosticStatus() == DiagnosticStatus.ENCODING_MISMATCH,
                "Mapped used code " + m.getCode() + " (" + m.getUnicode()
                    + ") should be OK or ENCODING_MISMATCH, got: " + m.getDiagnosticStatus());
        }
    }

    @Test
    void campingliste_glyphNameNotNull() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(campinglisteBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                campinglisteBytes, entry.getObjectNumber(), entry.getGenerationNumber());

            for (GlyphMapping m : detail.getGlyphMappings()) {
                assertNotNull(m.getGlyphName(),
                    "GlyphName should not be null for code " + m.getCode()
                        + " in font " + entry.getFontName());
            }
        }
    }

    @Test
    void campingliste_diagnosticStatusNeverNull() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(campinglisteBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                campinglisteBytes, entry.getObjectNumber(), entry.getGenerationNumber());

            for (GlyphMapping m : detail.getGlyphMappings()) {
                assertNotNull(m.getDiagnosticStatus(),
                    "DiagnosticStatus should not be null for code " + m.getCode()
                        + " in font " + entry.getFontName());
            }
        }
    }

    @Test
    void generatedPdf_renderAndExtractionStatusesPopulated() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(generatedTestPdfBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                generatedTestPdfBytes, entry.getObjectNumber(), entry.getGenerationNumber());

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
        FontDiagnostics diagnostics = service.analyzeFontIssues(generatedTestPdfBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                generatedTestPdfBytes, entry.getObjectNumber(), entry.getGenerationNumber());

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
        FontDiagnostics diagnostics = service.analyzeFontIssues(generatedTestPdfBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                generatedTestPdfBytes, entry.getObjectNumber(), entry.getGenerationNumber());

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

    @Test
    void campingliste_subsetCompleteForEmbeddedFonts() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(campinglisteBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.isEmbedded() && entry.isSubset()) {
                // Well-formed Campingliste should have complete subsets
                assertTrue(entry.isSubsetComplete(),
                    "Embedded subset font " + entry.getFontName()
                        + " in Campingliste.pdf should have subsetComplete=true");
            }
        }
    }

    @Test
    void campingliste_encodingDiagnosticsPopulated() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(campinglisteBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                campinglisteBytes, entry.getObjectNumber(), entry.getGenerationNumber());

            EncodingDiagnostics enc = detail.getEncoding();
            assertNotNull(enc, "EncodingDiagnostics should not be null for " + entry.getFontName());
            // Every font should have encoding or subtype info
            assertNotNull(enc.getSubtype(),
                "subtype should be populated for " + entry.getFontName());

            // Type0 fonts should have descendant info and CMap name
            if ("Type0Font".equals(entry.getFontType())) {
                assertNotNull(enc.getCmapName(),
                    "cmapName should be populated for Type0 font " + entry.getFontName());
                assertNotNull(enc.getDescendantSubtype(),
                    "descendantSubtype should be populated for Type0 font " + entry.getFontName());
            }
        }
    }

    @Test
    void missingUsedMappings_isConsistentSubsetOfAll() throws IOException {
        FontDiagnostics diagnostics = service.analyzeFontIssues(campinglisteBytes);

        for (FontDiagnosticsEntry entry : diagnostics.getFonts()) {
            if (entry.getObjectNumber() < 0) continue;

            FontDiagnosticsDetail detail = service.analyzeFontIssueDetail(
                campinglisteBytes, entry.getObjectNumber(), entry.getGenerationNumber());

            List<GlyphMapping> expectedMissing = detail.getGlyphMappings().stream()
                .filter(m -> m.getUsedCount() > 0 && !m.isMapped())
                .toList();

            Set<Integer> expectedCodes = expectedMissing.stream()
                .map(GlyphMapping::getCode)
                .collect(Collectors.toSet());

            Set<Integer> actualCodes = detail.getMissingUsedGlyphMappings().stream()
                .map(GlyphMapping::getCode)
                .collect(Collectors.toSet());

            assertEquals(expectedCodes, actualCodes,
                "missingUsedGlyphMappings codes should match filtered glyphMappings for "
                    + entry.getFontName());

            assertEquals(expectedMissing.size(), detail.getMissingUsedGlyphMappings().size(),
                "missingUsedGlyphMappings count should match for " + entry.getFontName());
        }
    }
}
