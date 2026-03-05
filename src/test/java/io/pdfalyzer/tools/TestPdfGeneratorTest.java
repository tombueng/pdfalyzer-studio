package io.pdfalyzer.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled("PDF generator tool — run manually when regenerating test fixtures")
class TestPdfGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void embedsFontsFromNestedSubdirectories() throws Exception {
        Path fontsDir = tempDir.resolve("fonts");
        Path nestedDir = fontsDir.resolve("nested").resolve("deeper");
        Files.createDirectories(nestedDir);

        Path ttfSource = Path.of("src", "test", "resources", "fonts", "strange-web", "extreme-symbols", "NotoSansLinearA-Regular.ttf");
        Path ttfSource2 = Path.of("src", "test", "resources", "fonts", "strange-web", "extreme-symbols", "NotoSansLinearB-Regular.ttf");

        Files.copy(ttfSource, nestedDir.resolve("NotoSansLinearA-Regular.ttf"));
        Files.copy(ttfSource2, nestedDir.resolve("NotoSansLinearB-Regular.ttf"));

        TestPdfGenerator.main(new String[]{tempDir.toString()});

        assertTrue(Files.exists(tempDir.resolve("fonts").resolve("strange-web").resolve("extreme-symbols").resolve("NotoSansLinearA-Regular.ttf")),
            "Generator must mirror nested font resources from src/test/resources/fonts");

        Path outputPdf = tempDir.resolve("test.pdf");
        assertTrue(Files.exists(outputPdf), "Generator must create test.pdf");

        try (PDDocument document = Loader.loadPDF(outputPdf.toFile())) {
            PDDocumentNameDictionary names = document.getDocumentCatalog().getNames();
            assertNotNull(names, "PDF catalog names dictionary must exist");

            PDEmbeddedFilesNameTreeNode embeddedFiles = names.getEmbeddedFiles();
            assertNotNull(embeddedFiles, "Embedded files tree must exist");

            Map<String, PDComplexFileSpecification> fileMap = embeddedFiles.getNames();
            assertNotNull(fileMap, "Embedded files map must exist");

            assertTrue(fileMap.containsKey("nested/deeper/NotoSansLinearA-Regular.ttf"),
                "Nested TTF must be embedded with relative path key");
            assertTrue(fileMap.containsKey("nested/deeper/NotoSansLinearB-Regular.ttf"),
                "Second nested TTF must be embedded with relative path key");
        }
    }
}
