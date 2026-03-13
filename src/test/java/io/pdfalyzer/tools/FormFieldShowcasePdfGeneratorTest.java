package io.pdfalyzer.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("PDF generator tool — run manually when regenerating sample fixtures")
class FormFieldShowcasePdfGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesValidPdfWithAcroForm() throws Exception {
        Path outFile = tempDir.resolve("form-fields-showcase.pdf");
        FormFieldShowcasePdfGenerator.main(new String[]{outFile.toString()});

        assertTrue(Files.exists(outFile), "PDF must be written to the output path");
        assertTrue(Files.size(outFile) > 10_000, "PDF must be non-trivial in size");

        try (PDDocument doc = Loader.loadPDF(outFile.toFile())) {
            assertTrue(doc.getNumberOfPages() >= 3, "Showcase must have at least 3 pages");

            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            assertNotNull(form, "AcroForm dictionary must be present");
            assertFalse(form.getFields().isEmpty(), "AcroForm must contain fields");

            assertNotNull(form.getDefaultResources(), "AcroForm /DR must be present");
            assertTrue(form.getDefaultResources().getFontNames().iterator().hasNext(),
                "AcroForm /DR /Font must contain at least one embedded font");
        }
    }

    /** Regenerate the committed showcase PDF into the sample-pdfs directory. */
    @Test
    void regenerateCommittedShowcasePdf() throws Exception {
        FormFieldShowcasePdfGenerator.main(new String[0]);
        Path committed = Path.of("src/main/resources/sample-pdfs/form-fields-showcase.pdf");
        assertTrue(Files.exists(committed), "Committed showcase PDF must exist");
        assertTrue(Files.size(committed) > 10_000, "Committed showcase PDF must be non-trivial");
    }
}
