package io.pdfalyzer.tools;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled("PDF generator tool — run manually when regenerating missing-glyph sample PDF")
class MissingGlyphEasyPdfGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesPdfForManualAdobeInspection() throws Exception {
        Path out = tempDir.resolve("missing-glyph-easy.pdf");
        MissingGlyphEasyPdfGenerator.generate(out);

        assertTrue(Files.exists(out), "Generated PDF should exist");
        assertTrue(Files.size(out) > 0, "Generated PDF should be non-empty");
    }
}
