package io.pdfalyzer.tools;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class MissingGlyphEasyPdfGenerator {

    public static void main(String[] args) throws Exception {
        Path outputPath = args != null && args.length > 0
            ? Paths.get(args[0])
            : Paths.get("target", "generated-test-pdfs", "missing-glyph-easy.pdf");

        generate(outputPath);
        System.out.println("Generated: " + outputPath.toAbsolutePath());
    }

    public static void generate(Path outputPath) throws IOException {
        if (outputPath == null) {
            throw new IllegalArgumentException("outputPath must not be null");
        }
        Files.createDirectories(outputPath.toAbsolutePath().getParent());

        Path fontPath = Paths.get("src", "test", "resources", "fonts", "FreeSans.ttf");

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDType0Font probeFont = createProbeFont(doc, fontPath);
            if (!probeFont.isEmbedded()) {
                throw new IllegalStateException("Probe font is not embedded; aborting generator to avoid misleading output.");
            }
            probeFont.getCOSObject().setItem(COSName.TO_UNICODE, createEmptyToUnicodeStream(doc));

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(probeFont, 12);
                cs.newLineAtOffset(50, 780);
                cs.showText("Missing Glyph Easy Test");
                cs.endText();

                cs.beginText();
                cs.setFont(probeFont, 10);
                cs.newLineAtOffset(50, 760);
                cs.showText("Line A uses normal text with subset font (should render fine):");
                cs.endText();

                cs.beginText();
                cs.setFont(probeFont, 16);
                cs.newLineAtOffset(50, 740);
                cs.showText("VISIBLE subset text: ABCDE abcde 12345");
                cs.endText();

                cs.beginText();
                cs.setFont(probeFont, 10);
                cs.newLineAtOffset(50, 710);
                cs.showText("Line B uses raw high CIDs in same subset font (expect missing glyph boxes/.notdef):");
                cs.endText();
            }

            appendRawCidProbe(page, doc, probeFont, 50, 685, "FFF0FFF1FFF2FFF3FFF4FFF5FFF6FFF7");
            appendRawCidProbe(page, doc, probeFont, 50, 660, "D000D001D002D003D004D005D006D007");

            doc.save(outputPath.toFile());
        }

        verifyGeneratedPdfContainsEmbeddedType0Subset(outputPath);
    }

    private static PDType0Font createProbeFont(PDDocument doc, Path fontPath) throws IOException {
        if (fontPath == null || !Files.exists(fontPath)) {
            throw new IllegalStateException("Required probe font file is missing: " + fontPath);
        }
        try (InputStream inputStream = Files.newInputStream(fontPath)) {
            return PDType0Font.load(doc, inputStream, true);
        }
    }

    private static COSStream createEmptyToUnicodeStream(PDDocument doc) throws IOException {
        String cmap = "/CIDInit /ProcSet findresource begin\n"
            + "12 dict begin\n"
            + "begincmap\n"
            + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
            + "/CMapName /Adobe-Identity-UCS def\n"
            + "/CMapType 2 def\n"
            + "1 begincodespacerange\n"
            + "<0000> <FFFF>\n"
            + "endcodespacerange\n"
            + "endcmap\n"
            + "CMapName currentdict /CMap defineresource pop\n"
            + "end\n"
            + "end\n";
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream()) {
            out.write(cmap.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }

    private static void appendRawCidProbe(PDPage page, PDDocument doc, PDFont font, int x, int y, String hexCodes) throws IOException {
        PDResources resources = page.getResources();
        if (resources == null) {
            resources = new PDResources();
            page.setResources(resources);
        }
        COSName fontResourceName = resources.add(font);

        String ops = "BT\n"
            + "/" + fontResourceName.getName() + " 18 Tf\n"
            + x + " " + y + " Td\n"
            + "<" + hexCodes + "> Tj\n"
            + "ET\n";

        PDStream append = new PDStream(doc);
        try (OutputStream out = append.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(ops.getBytes(StandardCharsets.US_ASCII));
        }

        COSBase existing = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
        COSStream appendStream = append.getCOSObject();
        if (existing instanceof COSArray array) {
            array.add(appendStream);
        } else if (existing instanceof COSStream stream) {
            COSArray array = new COSArray();
            array.add(stream);
            array.add(appendStream);
            page.getCOSObject().setItem(COSName.CONTENTS, array);
        } else {
            page.getCOSObject().setItem(COSName.CONTENTS, appendStream);
        }
    }

    private static void verifyGeneratedPdfContainsEmbeddedType0Subset(Path outputPath) throws IOException {
        List<String> details = new ArrayList<>();
        boolean foundEmbeddedType0Subset = false;

        try (PDDocument doc = Loader.loadPDF(outputPath.toFile())) {
            int pageIndex = 0;
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    pageIndex++;
                    continue;
                }
                for (COSName fontName : resources.getFontNames()) {
                    PDFont font = resources.getFont(fontName);
                    String name;
                    try {
                        name = font.getName();
                    } catch (Exception ignored) {
                        name = "(unknown)";
                    }
                    boolean isSubset = name != null && name.contains("+");
                    boolean isEmbedded = font.isEmbedded();
                    boolean isType0 = font instanceof PDType0Font;

                    details.add("page=" + pageIndex
                        + " res=" + fontName.getName()
                        + " name=" + name
                        + " type=" + font.getClass().getSimpleName()
                        + " embedded=" + isEmbedded
                        + " subset=" + isSubset);

                    if (isType0 && isEmbedded && isSubset) {
                        foundEmbeddedType0Subset = true;
                    }
                }
                pageIndex++;
            }
        }

        if (!foundEmbeddedType0Subset) {
            throw new IllegalStateException(
                "Generated PDF did not contain an embedded Type0 subset font. Details: " + String.join(" | ", details)
            );
        }

        System.out.println("Font verification: " + String.join(" | ", details));
    }
}
