package io.pdfalyzer.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Regenerates the encrypted sample PDFs under
 * {@code src/main/resources/sample-pdfs/}.
 *
 * Run with {@code -Dgenerate.samples=true} to actually write files:
 * <pre>
 *   mvn test -Dtest=EncryptedPdfGeneratorTest -Dgenerate.samples=true
 * </pre>
 */
class EncryptedPdfGeneratorTest {

    @Test
    @EnabledIfSystemProperty(named = "generate.samples", matches = "true")
    void generateEncryptedSamplePdfs() throws Exception {
        Path dir = Paths.get("src", "main", "resources", "sample-pdfs");
        EncryptedPdfGenerator.generateAll(dir);
    }
}
