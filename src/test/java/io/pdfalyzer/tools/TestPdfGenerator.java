package io.pdfalyzer.tools;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class TestPdfGenerator {

    private static final float PAGE_MARGIN = 36f;

    private record EmbeddedFontSet(
        PDFont heading,
        PDFont body,
        PDFont freeSans,
        PDFont lato,
        PDFont sourceSans,
        PDFont notoSerif,
        boolean sourceSansAvailable
    ) {}

    private enum FontAssetType {
        TRUE_TYPE,
        OPEN_TYPE,
        TRUE_TYPE_COLLECTION,
        TYPE1_PFB,
        TYPE1_AFM,
        UNKNOWN
    }

    private record FontEmbeddingResult(
        Map<String, PDFont> embeddedFontsByFilename,
        List<String> reportLines,
        List<Path> discoveredFiles
    ) {
        PDFont get(String filename) {
            if (filename == null) {
                return null;
            }
            return embeddedFontsByFilename.get(filename.toLowerCase());
        }

        PDFont firstEmbeddedFont() {
            if (embeddedFontsByFilename.isEmpty()) {
                return null;
            }
            return embeddedFontsByFilename.values().iterator().next();
        }
    }

    public static void main(String[] args) throws Exception {
        boolean hasOutputArg = args.length > 0;
        Path outDir = hasOutputArg
            ? Paths.get(args[0]).toAbsolutePath().normalize()
            : Paths.get("src", "main","resources").toAbsolutePath().normalize();
        Path resourcesDir = hasOutputArg
            ? outDir
            : Paths.get("src", "main/resources").toAbsolutePath().normalize();

        Files.createDirectories(outDir);
        Files.createDirectories(resourcesDir);

        Path testResourcesDir = Paths.get("src", "test", "resources")
            .toAbsolutePath().normalize();
        Files.createDirectories(testResourcesDir);

        Path testResourcesFontsDir = Paths.get("src", "test", "resources", "fonts")
            .toAbsolutePath().normalize();
        Files.createDirectories(testResourcesFontsDir);

        Path fontsDir = outDir.resolve("fonts");
        Path imagesDir = outDir.resolve("images");
        Path attachmentsDir = outDir.resolve("attachments");
        Files.createDirectories(fontsDir);
        Files.createDirectories(imagesDir);
        Files.createDirectories(attachmentsDir);

        Path freeSans = ensureFont(testResourcesFontsDir.resolve("FreeSans.ttf"),
                Arrays.asList(
                        "https://ftp.gnu.org/gnu/freefont/freefont-ttf-20120503.zip"
            ));
        Path lato = ensureFont(testResourcesFontsDir.resolve("Lato-Regular.ttf"),
                Arrays.asList("https://raw.githubusercontent.com/google/fonts/main/ofl/lato/Lato-Regular.ttf"));
        Path sourceSans = ensureFont(testResourcesFontsDir.resolve("SourceSans3-Regular.ttf"),
            Arrays.asList("https://raw.githubusercontent.com/google/fonts/main/ofl/sourcesans3/SourceSans3-Regular.ttf"));
        Path notoSerif = ensureFont(testResourcesFontsDir.resolve("NotoSerif-Regular.ttf"),
            Arrays.asList("https://raw.githubusercontent.com/googlefonts/noto-fonts/main/hinted/ttf/NotoSerif/NotoSerif-Regular.ttf"));

        Path urwBase35Zip = ensureBinary(testResourcesFontsDir.resolve("urw-base35.zip"),
            Arrays.asList("https://mirrors.ctan.org/fonts/urw/base35.zip"));
        Path urwType1Pfb = ensureZipEntry(
            testResourcesFontsDir.resolve("utmr8a.pfb"),
            urwBase35Zip,
            "base35/pfb/utmr8a.pfb"
        );
        Path urwType1Afm = ensureZipEntry(
            testResourcesFontsDir.resolve("utmr8a.afm"),
            urwBase35Zip,
            "base35/afm/utmr8a.afm"
        );

        mirrorDirectoryTree(testResourcesDir, outDir);
        copyIfPresent(freeSans, fontsDir.resolve("FreeSans.ttf"));
        copyIfPresent(lato, fontsDir.resolve("Lato-Regular.ttf"));
        copyIfPresent(sourceSans, fontsDir.resolve("SourceSans3-Regular.ttf"));
        copyIfPresent(notoSerif, fontsDir.resolve("NotoSerif-Regular.ttf"));
        copyIfPresent(urwType1Pfb, fontsDir.resolve("utmr8a.pfb"));
        copyIfPresent(urwType1Afm, fontsDir.resolve("utmr8a.afm"));

        Path freeSansForPdf = fontsDir.resolve("FreeSans.ttf");
        if (!Files.exists(freeSansForPdf)) freeSansForPdf = freeSans;
        Path latoForPdf = fontsDir.resolve("Lato-Regular.ttf");
        if (!Files.exists(latoForPdf)) latoForPdf = lato;
        Path sourceSansForPdf = fontsDir.resolve("SourceSans3-Regular.ttf");
        if (!Files.exists(sourceSansForPdf)) sourceSansForPdf = sourceSans;
        Path notoSerifForPdf = fontsDir.resolve("NotoSerif-Regular.ttf");
        if (!Files.exists(notoSerifForPdf)) notoSerifForPdf = notoSerif;

        Path jpg = createJpg(imagesDir.resolve("sample-photo.jpg"));
        Path png = createTransparentPng(imagesDir.resolve("sample-transparent.png"));
        Path jp2 = ensureBinary(imagesDir.resolve("sample-jpeg2000.jp2"),
                Arrays.asList(
                        "https://github.com/uclouvain/openjpeg-data/raw/master/input/nonregression/kodak_2layers_lrcp.jp2",
                        "https://raw.githubusercontent.com/AcademySoftwareFoundation/openexr-images/main/ScanLines/Desk.jp2"
                ));
        if (jp2 == null || !Files.exists(jp2)) {
            jp2 = createJp2Placeholder(imagesDir.resolve("sample-jpeg2000.jp2"), png);
        }

        Path zipAttachment = createZipAttachment(attachmentsDir.resolve("sample.zip"));
        Path pdfAttachment = createAttachmentPdf(attachmentsDir.resolve("sample-attachment.pdf"), freeSansForPdf);
        Path xlsAttachment = createAttachmentXls(attachmentsDir.resolve("sample.xls"));

        Path outputPdf = resourcesDir.resolve("test.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage(PDRectangle.LETTER);
            PDPage page2 = new PDPage(PDRectangle.LETTER);
            PDPage page3 = new PDPage(PDRectangle.LETTER);
            PDPage page4 = new PDPage(PDRectangle.LETTER);
            doc.addPage(page1);
            doc.addPage(page2);
            doc.addPage(page3);
            doc.addPage(page4);

            FontEmbeddingResult embeddingResult = embedFontsFromDirectory(doc, fontsDir);

            PDFont headingFont = pickEmbeddedFont(
                embeddingResult,
                "NotoSerif-Regular.ttf",
                "Lato-Regular.ttf",
                "FreeSans.ttf"
            );
            PDFont bodyFont = pickEmbeddedFont(
                embeddingResult,
                "FreeSans.ttf",
                "Lato-Regular.ttf",
                "NotoSerif-Regular.ttf"
            );
            PDFont freeSansFont = pickEmbeddedFont(embeddingResult, "FreeSans.ttf");
            PDFont latoFont = pickEmbeddedFont(embeddingResult, "Lato-Regular.ttf");
            PDFont notoSerifFont = pickEmbeddedFont(embeddingResult, "NotoSerif-Regular.ttf");
            PDFont optionalSourceSans = embeddingResult.get("SourceSans3-Regular.ttf");

            if (headingFont == null) {
                headingFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            }
            if (bodyFont == null) {
                bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            }
            if (freeSansFont == null) {
                freeSansFont = bodyFont;
            }
            if (latoFont == null) {
                latoFont = bodyFont;
            }
            if (notoSerifFont == null) {
                notoSerifFont = headingFont;
            }

            EmbeddedFontSet fonts = new EmbeddedFontSet(
                headingFont,
                bodyFont,
                freeSansFont,
                latoFont,
                optionalSourceSans != null ? optionalSourceSans : notoSerifFont,
                notoSerifFont,
                optionalSourceSans != null
            );

            renderPage1Images(doc, page1, jpg, png, jp2, fonts.heading(), fonts.body());
            renderPage2Fonts(doc, page2, fonts, embeddingResult.reportLines());
            renderPage3LinksAndAttachments(
                doc,
                page3,
                page2,
                zipAttachment,
                pdfAttachment,
                xlsAttachment,
                fontsDir,
                embeddingResult.discoveredFiles(),
                fonts.heading(),
                fonts.body()
            );
            renderPage4Forms(doc, page4, fonts.heading(), fonts.body());
            addBookmarks(doc, page1, page2, page3, page4);
            applyDocumentMetadata(doc);

            doc.save(outputPdf.toFile());
        }

        verifyEmbeddedFontFiles(outputPdf, fontsDir);

        System.out.println("Generated PDF: " + outputPdf);
        System.out.println("Assets folder : " + outDir);
        System.out.println("Resources dir : " + resourcesDir);
        System.out.println("Font cache    : " + testResourcesFontsDir);
    }

    private static void renderPage1Images(PDDocument doc, PDPage page, Path jpg, Path png, Path jp2, PDFont headingFont, PDFont bodyFont) throws IOException {
        PDImageXObject jpgImage = PDImageXObject.createFromFileByContent(jpg.toFile(), doc);
        PDImageXObject pngImage = PDImageXObject.createFromFileByContent(png.toFile(), doc);
        PDImageXObject alphaImage = LosslessFactory.createFromImage(doc, createAlphaMaskImage());

        PDImageXObject jp2Image = null;
        if (jp2 != null && Files.exists(jp2)) {
            try {
                jp2Image = PDImageXObject.createFromFileByContent(jp2.toFile(), doc);
            } catch (Exception ignored) {
                jp2Image = null;
            }
        }

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            writeHeading(cs, headingFont, "Page 1: Images + Transparency / SMask", page.getMediaBox().getHeight() - 36);

            cs.drawImage(jpgImage, PAGE_MARGIN, 500, 230, 160);
            drawCaption(cs, bodyFont, PAGE_MARGIN, 488, "JPEG image (opaque)");

            cs.drawImage(pngImage, PAGE_MARGIN + 250, 500, 230, 160);
            drawCaption(cs, bodyFont, PAGE_MARGIN + 250, 488, "PNG image (alpha transparency)");

            cs.drawImage(alphaImage, PAGE_MARGIN, 280, 230, 160);
            drawCaption(cs, bodyFont, PAGE_MARGIN, 268, "Lossless ARGB image (soft mask)");

            if (jp2Image != null) {
                cs.drawImage(jp2Image, PAGE_MARGIN + 250, 280, 230, 160);
            drawCaption(cs, bodyFont, PAGE_MARGIN + 250, 268, "JPEG2000 image (JPX decode)");
            } else {
                drawBox(cs, PAGE_MARGIN + 250, 280, 230, 160);
            drawCaption(cs, bodyFont, PAGE_MARGIN + 258, 350, "JPEG2000 test asset present, renderer unavailable on this JVM");
            drawCaption(cs, bodyFont, PAGE_MARGIN + 258, 333, "(see images/sample-jpeg2000.jp2)");
            }
        }
    }

    private static void renderPage2Fonts(PDDocument doc, PDPage page, EmbeddedFontSet fonts, List<String> embeddingReport) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            writeHeading(cs, fonts.heading(), "Page 2: Fonts (all embedded)", page.getMediaBox().getHeight() - 36);

            drawTextLine(cs, fonts.body(), 13, PAGE_MARGIN, 700,
                "Embedded TTF: FreeSans.ttf (UI default)");
            drawTextLine(cs, fonts.body(), 11, PAGE_MARGIN, 682,
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz 0123456789");

            drawTextLine(cs, fonts.freeSans(), 15, PAGE_MARGIN, 640,
                    "Embedded TTF: FreeSans.ttf");
            drawTextLine(cs, fonts.freeSans(), 12, PAGE_MARGIN, 622,
                    "The quick brown fox jumps over the lazy dog. 1234567890");

            drawTextLine(cs, fonts.lato(), 15, PAGE_MARGIN, 580,
                    "Embedded TTF: Lato-Regular.ttf");
            drawTextLine(cs, fonts.lato(), 12, PAGE_MARGIN, 562,
                    "Sphinx of black quartz, judge my vow. ÀÉÍÕÜ ñ ç");

                if (fonts.sourceSansAvailable()) {
                drawTextLine(cs, fonts.sourceSans(), 15, PAGE_MARGIN, 520,
                    "Embedded TTF: SourceSans3-Regular.ttf");
                drawTextLine(cs, fonts.sourceSans(), 12, PAGE_MARGIN, 502,
                    "Pack my box with five dozen liquor jugs. € £ ¥");
                } else {
                drawTextLine(cs, fonts.notoSerif(), 12, PAGE_MARGIN, 502,
                    "SourceSans3-Regular.ttf unavailable locally; embedded fonts still include all available TTF assets.");
                }

            drawTextLine(cs, fonts.notoSerif(), 15, PAGE_MARGIN, 460,
                "Embedded TTF: NotoSerif-Regular.ttf");
            drawTextLine(cs, fonts.notoSerif(), 12, PAGE_MARGIN, 442,
                "Voix ambiguë d’un cœur qui, au zéphyr, préfère les jattes de kiwis.");

            drawTextLine(cs, fonts.body(), 10, PAGE_MARGIN, 406,
                "All listed fonts are embedded from /fonts next to the generated PDF.");

            drawTextLine(cs, fonts.body(), 10, PAGE_MARGIN, 384,
                "Auto-discovery report from /fonts:");
            float y = 368;
            for (String line : embeddingReport) {
                if (y < 54) {
                    break;
                }
                drawTextLine(cs, fonts.body(), 8.5f, PAGE_MARGIN, y, line);
                y -= 12;
            }
        }
    }

    private static void renderPage3LinksAndAttachments(
            PDDocument doc,
            PDPage page,
            PDPage jumpTargetPage,
            Path zipAttachment,
            Path pdfAttachment,
            Path xlsAttachment,
                Path fontsRootDir,
            List<Path> fontFiles,
            PDFont headingFont,
            PDFont bodyFont
    ) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeHeading(cs, headingFont, "Page 3: Links, Bookmarks, Attachments", page.getMediaBox().getHeight() - 36);
                drawTextLine(cs, bodyFont, 12, PAGE_MARGIN, 700,
                    "External link: https://www.example.com");
                drawTextLine(cs, bodyFont, 12, PAGE_MARGIN, 670,
                    "Internal link: jump to Page 2");
                drawTextLine(cs, bodyFont, 12, PAGE_MARGIN, 640,
                    "Embedded attachments: sample.zip, sample-attachment.pdf, sample.xls");
        }

        addExternalLink(page, PAGE_MARGIN, 694, 240, 16, "https://www.example.com");
        addInternalLink(page, PAGE_MARGIN, 664, 180, 16, jumpTargetPage);

        Map<String, PDComplexFileSpecification> embedded = new LinkedHashMap<>();
        embedded.put("sample.zip", buildEmbeddedFile(doc, zipAttachment, "application/zip"));
        embedded.put("sample-attachment.pdf", buildEmbeddedFile(doc, pdfAttachment, "application/pdf"));
        embedded.put("sample.xls", buildEmbeddedFile(doc, xlsAttachment, "application/vnd.ms-excel"));
        for (Path fontFile : fontFiles) {
            String entryName = toEmbeddedFontEntryName(fontsRootDir, fontFile);
            if (!embedded.containsKey(entryName)) {
                embedded.put(entryName, buildEmbeddedFile(doc, fontFile, entryName, detectFontMimeType(fontFile)));
            }
        }

        PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
        PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
        tree.setNames(embedded);
        names.setEmbeddedFiles(tree);
        doc.getDocumentCatalog().setNames(names);

        addAttachmentAnnotation(page, embedded.get("sample.zip"), PAGE_MARGIN, 600);
        addAttachmentAnnotation(page, embedded.get("sample-attachment.pdf"), PAGE_MARGIN + 20, 600);
        addAttachmentAnnotation(page, embedded.get("sample.xls"), PAGE_MARGIN + 40, 600);
    }

    private static void renderPage4Forms(PDDocument doc, PDPage page, PDFont headingFont, PDFont bodyFont) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            writeHeading(cs, headingFont, "Page 4: AcroForm Fields", page.getMediaBox().getHeight() - 36);
            drawTextLine(cs, bodyFont, 12, PAGE_MARGIN, 720, "Text (required), text (readonly), multiline text");
            drawTextLine(cs, bodyFont, 12, PAGE_MARGIN, 680, "Checkbox, radio, combo box, list box, signature field, JS validation fields");

            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 646, "Name (required):");
            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 616, "Account (readonly):");
            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 586, "Notes (multiline):");
            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 496, "Agree checkbox:");
            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 466, "Radio option:");
            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 436, "Combo (country):");
            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 406, "List (priority):");
            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 348, "Age (18-99, JS validate):");
            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 318, "Date (YYYY-MM-DD, JS validate):");
            drawTextLine(cs, bodyFont, 10, PAGE_MARGIN, 278, "Signature:");
        }

        PDAcroForm form = new PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(form);
        form.setNeedAppearances(true);

        PDResources resources = new PDResources();
        COSName fontName = resources.add(bodyFont);
        form.setDefaultResources(resources);
        form.setDefaultAppearance("/" + fontName.getName() + " 10 Tf 0 g");

        List<org.apache.pdfbox.pdmodel.interactive.form.PDField> fields = form.getFields();

        PDTextField requiredText = new PDTextField(form);
        requiredText.setPartialName("name_required");
        requiredText.setRequired(true);
        addWidget(requiredText.getWidgets().get(0), page, 160, 640, 250, 18);
        requiredText.setValue("Jane Doe");
        fields.add(requiredText);

        PDTextField readonlyText = new PDTextField(form);
        readonlyText.setPartialName("account_readonly");
        readonlyText.setReadOnly(true);
        addWidget(readonlyText.getWidgets().get(0), page, 160, 610, 250, 18);
        readonlyText.setValue("ACC-2026-0001");
        fields.add(readonlyText);

        PDTextField multilineText = new PDTextField(form);
        multilineText.setPartialName("notes_multiline");
        multilineText.setMultiline(true);
        addWidget(multilineText.getWidgets().get(0), page, 160, 540, 330, 54);
        multilineText.setValue("Line 1: sample note\nLine 2: another value");
        fields.add(multilineText);

        PDCheckBox checkbox = new PDCheckBox(form);
        checkbox.setPartialName("agree_checkbox");
        checkbox.setRequired(true);
        addWidget(checkbox.getWidgets().get(0), page, 160, 492, 14, 14);
        checkbox.check();
        fields.add(checkbox);

        PDRadioButton radio = new PDRadioButton(form);
        radio.setPartialName("radio_contact");
        radio.setExportValues(Arrays.asList("email"));
        addWidget(radio.getWidgets().get(0), page, 160, 462, 14, 14);
        radio.setValue("email");
        fields.add(radio);

        PDComboBox combo = new PDComboBox(form);
        combo.setPartialName("country_combo");
        combo.setOptions(Arrays.asList("DE", "US", "FR", "JP"));
        addWidget(combo.getWidgets().get(0), page, 160, 430, 120, 18);
        combo.setValue("DE");
        fields.add(combo);

        PDListBox list = new PDListBox(form);
        list.setPartialName("priority_list");
        list.setOptions(Arrays.asList("Low", "Medium", "High", "Critical"));
        addWidget(list.getWidgets().get(0), page, 160, 370, 140, 52);
        list.setValue("High");
        fields.add(list);

        PDTextField ageValidated = new PDTextField(form);
        ageValidated.setPartialName("age_min_max_js");
        addWidget(ageValidated.getWidgets().get(0), page, 160, 342, 120, 18);
        ageValidated.setValue("25");
        setValidationJavaScript(ageValidated,
                "if (event.value !== '') { " +
                        "var n = Number(event.value); " +
                        "if (isNaN(n) || n < 18 || n > 99) { " +
                        "app.alert('Age must be between 18 and 99'); " +
                        "event.rc = false; " +
                        "}" +
                        "}");
        fields.add(ageValidated);

        PDTextField dateValidated = new PDTextField(form);
        dateValidated.setPartialName("date_format_js");
        addWidget(dateValidated.getWidgets().get(0), page, 160, 312, 140, 18);
        dateValidated.setValue("2026-03-01");
        setValidationJavaScript(dateValidated,
                "if (event.value !== '' && !/^\\d{4}-\\d{2}-\\d{2}$/.test(event.value)) { " +
                        "app.alert('Use YYYY-MM-DD date format'); " +
                        "event.rc = false; " +
                        "}");
        fields.add(dateValidated);

        PDSignatureField signature = new PDSignatureField(form);
        signature.setPartialName("signature_field");
        signature.setRequired(true);
        addWidget(signature.getWidgets().get(0), page, 160, 260, 250, 26);
        fields.add(signature);
    }

    private static void setValidationJavaScript(org.apache.pdfbox.pdmodel.interactive.form.PDField field,
                                                String javascriptCode) {
        if (javascriptCode == null) {
            return;
        }
        org.apache.pdfbox.cos.COSDictionary fieldDict = field.getCOSObject();
        org.apache.pdfbox.cos.COSDictionary aa =
                (org.apache.pdfbox.cos.COSDictionary) fieldDict.getDictionaryObject(org.apache.pdfbox.cos.COSName.AA);
        if (aa == null) {
            aa = new org.apache.pdfbox.cos.COSDictionary();
            fieldDict.setItem(org.apache.pdfbox.cos.COSName.AA, aa);
        }
        org.apache.pdfbox.cos.COSDictionary validateAction = new org.apache.pdfbox.cos.COSDictionary();
        validateAction.setName(org.apache.pdfbox.cos.COSName.S, "JavaScript");
        validateAction.setString(org.apache.pdfbox.cos.COSName.JS, javascriptCode);
        aa.setItem(org.apache.pdfbox.cos.COSName.V, validateAction);
    }

    private static void addWidget(PDAnnotationWidget widget, PDPage page, float x, float y, float width, float height) throws IOException {
        widget.setRectangle(new PDRectangle(x, y, width, height));
        widget.setPage(page);
        page.getAnnotations().add(widget);
    }

    private static void addBookmarks(PDDocument doc, PDPage page1, PDPage page2, PDPage page3, PDPage page4) {
        PDDocumentOutline outline = new PDDocumentOutline();
        doc.getDocumentCatalog().setDocumentOutline(outline);

        outline.addLast(createBookmark("1 - Images", page1));
        outline.addLast(createBookmark("2 - Fonts", page2));
        outline.addLast(createBookmark("3 - Links & Attachments", page3));
        outline.addLast(createBookmark("4 - Forms", page4));
        outline.openNode();
    }

    private static PDOutlineItem createBookmark(String title, PDPage page) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        PDPageFitDestination destination = new PDPageFitDestination();
        destination.setPage(page);
        item.setDestination(destination);
        return item;
    }

    private static void applyDocumentMetadata(PDDocument doc) throws IOException {
        ZonedDateTime now = ZonedDateTime.now();
        String nowIso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        PDDocumentInformation info = createDocumentInformation(
            "PDFalyzer Test PDF",
            "PDFalyzer",
            "Feature-rich sample PDF for UI and parser validation",
            "pdfalyzer,test,sample,images,fonts,forms,attachments,metadata,xmp",
            now
        );
        info.setCustomMetadataValue("GeneratorVersion", "1.0");
        info.setCustomMetadataValue("SampleType", "UI-Regression-Fixture");
        doc.setDocumentInformation(info);

        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        catalog.setLanguage("en-US");
        PDMarkInfo markInfo = new PDMarkInfo();
        markInfo.setMarked(true);
        catalog.setMarkInfo(markInfo);

        String xmp = buildXmpPacket(nowIso, info.getTitle(), info.getCreator(), info.getSubject(), info.getKeywords());
        PDMetadata metadata = new PDMetadata(doc);
        try (OutputStream os = metadata.createOutputStream()) {
            os.write(xmp.getBytes(StandardCharsets.UTF_8));
        }
        catalog.setMetadata(metadata);
    }

    private static PDDocumentInformation createDocumentInformation(
            String title,
            String author,
            String subject,
            String keywords,
            ZonedDateTime timestamp
    ) {
        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle(title);
        info.setAuthor(author);
        info.setCreator("TestPdfGenerator");
        info.setProducer("Apache PDFBox");
        info.setSubject(subject);
        info.setKeywords(keywords);
        info.setCreationDate(GregorianCalendar.from(timestamp));
        info.setModificationDate(GregorianCalendar.from(timestamp));
        return info;
    }

    private static String buildXmpPacket(String isoDate, String title, String creator, String subject, String keywords) {
        String safeTitle = xmlEscape(title);
        String safeCreator = xmlEscape(creator);
        String safeSubject = xmlEscape(subject);
        String safeKeywords = xmlEscape(keywords);

        return "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
                + "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "    <rdf:Description rdf:about=\"\"\n"
                + "      xmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n"
                + "      xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n"
                + "      xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n"
                + "      <dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" + safeTitle + "</rdf:li></rdf:Alt></dc:title>\n"
                + "      <dc:creator><rdf:Seq><rdf:li>" + safeCreator + "</rdf:li></rdf:Seq></dc:creator>\n"
                + "      <dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">" + safeSubject + "</rdf:li></rdf:Alt></dc:description>\n"
                + "      <pdf:Keywords>" + safeKeywords + "</pdf:Keywords>\n"
                + "      <xmp:CreateDate>" + isoDate + "</xmp:CreateDate>\n"
                + "      <xmp:ModifyDate>" + isoDate + "</xmp:ModifyDate>\n"
                + "      <xmp:CreatorTool>TestPdfGenerator</xmp:CreatorTool>\n"
                + "    </rdf:Description>\n"
                + "  </rdf:RDF>\n"
                + "</x:xmpmeta>\n"
                + "<?xpacket end=\"w\"?>";
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static void addExternalLink(PDPage page, float x, float y, float width, float height, String uri) throws IOException {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(x, y, width, height));
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        link.setBorderStyle(border);
        PDActionURI action = new PDActionURI();
        action.setURI(uri);
        link.setAction(action);
        page.getAnnotations().add(link);
    }

    private static void addInternalLink(PDPage page, float x, float y, float width, float height, PDPage destinationPage) throws IOException {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(x, y, width, height));
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        link.setBorderStyle(border);

        PDPageFitDestination destination = new PDPageFitDestination();
        destination.setPage(destinationPage);
        PDActionGoTo action = new PDActionGoTo();
        action.setDestination(destination);
        link.setAction(action);
        page.getAnnotations().add(link);
    }

    private static void addAttachmentAnnotation(PDPage page, PDComplexFileSpecification spec, float x, float y) throws IOException {
        PDAnnotationFileAttachment attachment = new PDAnnotationFileAttachment();
        attachment.setRectangle(new PDRectangle(x, y, 14, 14));
        attachment.setFile(spec);
        page.getAnnotations().add(attachment);
    }

    private static PDComplexFileSpecification buildEmbeddedFile(PDDocument doc, Path file, String mimeType) throws IOException {
        return buildEmbeddedFile(doc, file, file.getFileName().toString(), mimeType);
    }

    private static PDComplexFileSpecification buildEmbeddedFile(PDDocument doc, Path file, String displayName, String mimeType) throws IOException {
        byte[] data = Files.readAllBytes(file);
        PDEmbeddedFile embeddedFile = new PDEmbeddedFile(doc, new ByteArrayInputStream(data));
        embeddedFile.setSubtype(mimeType);
        embeddedFile.setSize(data.length);

        PDComplexFileSpecification spec = new PDComplexFileSpecification();
        spec.setFile(displayName);
        spec.setEmbeddedFile(embeddedFile);
        return spec;
    }

    private static FontEmbeddingResult embedFontsFromDirectory(PDDocument doc, Path fontsDir) throws IOException {
        List<Path> fontFiles = discoverFontFiles(fontsDir);
        Map<String, PDFont> embeddedFonts = new LinkedHashMap<>();
        List<String> report = new ArrayList<>();
        Map<String, Boolean> hasPfbByStem = new HashMap<>();
        Map<String, Boolean> hasAfmByStem = new HashMap<>();

        for (Path fontFile : fontFiles) {
            FontAssetType type = detectFontAssetType(fontFile);
            String fileName = toEmbeddedFontEntryName(fontsDir, fontFile);
            switch (type) {
                case TRUE_TYPE, OPEN_TYPE, TRUE_TYPE_COLLECTION -> {
                    try (InputStream inputStream = Files.newInputStream(fontFile)) {
                        PDFont font = PDType0Font.load(doc, inputStream, false);
                        embeddedFonts.put(fileName.toLowerCase(), font);
                        report.add(fileName + " => " + type + " -> embedded as PDF font");
                    } catch (Exception ex) {
                        report.add(fileName + " => " + type + " -> embed failed, kept as attachment asset");
                    }
                }
                case TYPE1_PFB -> {
                    hasPfbByStem.put(fileStem(fileName), true);
                    report.add(fileName + " => TYPE1_PFB -> embedded as attachment asset");
                }
                case TYPE1_AFM -> {
                    hasAfmByStem.put(fileStem(fileName), true);
                    report.add(fileName + " => TYPE1_AFM -> embedded as attachment asset");
                }
                case UNKNOWN -> report.add(fileName + " => UNKNOWN -> embedded as attachment asset");
            }
        }

        for (Map.Entry<String, Boolean> entry : hasPfbByStem.entrySet()) {
            String stem = entry.getKey();
            if (Boolean.TRUE.equals(entry.getValue()) && Boolean.TRUE.equals(hasAfmByStem.get(stem))) {
                report.add(stem + ".pfb + " + stem + ".afm => Type1 pair detected, embedded via attachment strategy");
            }
        }

        if (fontFiles.isEmpty()) {
            report.add("No files found in /fonts");
        }

        return new FontEmbeddingResult(embeddedFonts, report, fontFiles);
    }

    private static List<Path> discoverFontFiles(Path fontsDir) throws IOException {
        if (fontsDir == null || !Files.isDirectory(fontsDir)) {
            return List.of();
        }
        try (var stream = Files.walk(fontsDir)) {
            return stream
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path -> path.toString().toLowerCase()))
                .toList();
        }
    }

    private static FontAssetType detectFontAssetType(Path fontFile) {
        if (fontFile == null) {
            return FontAssetType.UNKNOWN;
        }
        String name = fontFile.getFileName().toString().toLowerCase();
        if (name.endsWith(".ttf")) {
            return FontAssetType.TRUE_TYPE;
        }
        if (name.endsWith(".otf")) {
            return FontAssetType.OPEN_TYPE;
        }
        if (name.endsWith(".ttc")) {
            return FontAssetType.TRUE_TYPE_COLLECTION;
        }
        if (name.endsWith(".pfb")) {
            return FontAssetType.TYPE1_PFB;
        }
        if (name.endsWith(".pfa") || name.endsWith(".t1")) {
            return FontAssetType.TYPE1_PFB;
        }
        if (name.endsWith(".afm")) {
            return FontAssetType.TYPE1_AFM;
        }

        try {
            byte[] header = Files.readAllBytes(fontFile);
            if (looksLikeOpenType(header)) {
                return FontAssetType.TRUE_TYPE;
            }
        } catch (Exception ignored) {
        }
        return FontAssetType.UNKNOWN;
    }

    private static PDFont pickEmbeddedFont(FontEmbeddingResult embeddingResult, String... preferredFilenames) {
        if (embeddingResult == null) {
            return null;
        }
        for (String filename : preferredFilenames) {
            PDFont font = embeddingResult.get(filename);
            if (font != null) {
                return font;
            }
        }
        return embeddingResult.firstEmbeddedFont();
    }

    private static String detectFontMimeType(Path fontFile) {
        if (fontFile == null) {
            return "application/octet-stream";
        }
        String name = fontFile.getFileName().toString().toLowerCase();
        if (name.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (name.endsWith(".otf")) {
            return "font/otf";
        }
        if (name.endsWith(".ttc")) {
            return "font/collection";
        }
        if (name.endsWith(".pfb")) {
            return "application/x-font-type1";
        }
        if (name.endsWith(".pfa") || name.endsWith(".t1")) {
            return "application/x-font-type1";
        }
        if (name.endsWith(".afm")) {
            return "application/x-font-afm";
        }
        return "application/octet-stream";
    }

    private static String toEmbeddedFontEntryName(Path fontsRootDir, Path fontFile) {
        if (fontFile == null) {
            return "";
        }
        if (fontsRootDir == null) {
            return fontFile.getFileName().toString();
        }
        try {
            Path relative = fontsRootDir.relativize(fontFile);
            return relative.toString().replace('\\', '/');
        } catch (Exception ignored) {
            return fontFile.getFileName().toString();
        }
    }

    private static String fileStem(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) {
            return filename.toLowerCase();
        }
        return filename.substring(0, dot).toLowerCase();
    }

    private static void verifyEmbeddedFontFiles(Path pdfPath, Path fontsDir) throws IOException {
        List<Path> discoveredFonts = discoverFontFiles(fontsDir);
        Set<String> expectedNames = new LinkedHashSet<>();
        for (Path fontPath : discoveredFonts) {
            expectedNames.add(toEmbeddedFontEntryName(fontsDir, fontPath));
        }

        Set<String> embeddedNames = new HashSet<>();
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDDocumentNameDictionary names = document.getDocumentCatalog().getNames();
            if (names != null) {
                PDEmbeddedFilesNameTreeNode embeddedFiles = names.getEmbeddedFiles();
                if (embeddedFiles != null) {
                    Map<String, PDComplexFileSpecification> fileMap = embeddedFiles.getNames();
                    if (fileMap != null) {
                        embeddedNames.addAll(fileMap.keySet());
                    }
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (String expected : expectedNames) {
            if (!embeddedNames.contains(expected)) {
                missing.add(expected);
            }
        }

        if (!missing.isEmpty()) {
            throw new IOException("Missing embedded font file entries in PDF: " + String.join(", ", missing));
        }

        System.out.println("Embedded font files verified: " + expectedNames.size() + " present in Embedded Files name tree.");
    }

    private static PDFont loadRequiredEmbeddedFont(PDDocument doc, Path fontPath, String fontLabel) throws IOException {
        if (fontPath == null || !Files.exists(fontPath)) {
            throw new IOException("Required font not found for embedding: " + fontLabel + " (" + fontPath + ")");
        }
        try {
            try (InputStream inputStream = Files.newInputStream(fontPath)) {
                return PDType0Font.load(doc, inputStream, false);
            }
        } catch (Exception ex) {
            throw new IOException("Could not load embedded font: " + fontLabel + " from " + fontPath, ex);
        }
    }

    private static PDFont loadOptionalEmbeddedFont(PDDocument doc, Path fontPath, String fontLabel) {
        if (fontPath == null || !Files.exists(fontPath)) {
            return null;
        }
        try {
            try (InputStream inputStream = Files.newInputStream(fontPath)) {
                return PDType0Font.load(doc, inputStream, false);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeHeading(PDPageContentStream cs, PDFont headingFont, String text, float y) throws IOException {
        cs.beginText();
        cs.setFont(headingFont, 16);
        cs.newLineAtOffset(PAGE_MARGIN, y);
        cs.showText(text);
        cs.endText();
    }

    private static void drawTextLine(PDPageContentStream cs, PDFont font, float fontSize, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static void drawCaption(PDPageContentStream cs, PDFont bodyFont, float x, float y, String text) throws IOException {
        drawTextLine(cs, bodyFont, 10, x, y, text);
    }

    private static void drawBox(PDPageContentStream cs, float x, float y, float w, float h) throws IOException {
        cs.addRect(x, y, w, h);
        cs.stroke();
    }

    private static Path createJpg(Path path) throws IOException {
        BufferedImage image = new BufferedImage(680, 420, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(35, 87, 137), 680, 420, new Color(236, 240, 241)));
        g.fillRect(0, 0, 680, 420);
        g.setColor(new Color(241, 196, 15));
        g.fillOval(60, 80, 200, 200);
        g.setColor(new Color(231, 76, 60));
        g.fillRect(320, 120, 260, 160);
        g.dispose();
        ImageIO.write(image, "jpg", path.toFile());
        return path;
    }

    private static Path createTransparentPng(Path path) throws IOException {
        BufferedImage image = new BufferedImage(680, 420, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.setColor(new Color(52, 152, 219, 90));
        g.fillRect(0, 0, 680, 420);
        g.setColor(new Color(46, 204, 113, 150));
        g.fill(new Ellipse2D.Float(90, 60, 250, 250));
        g.setColor(new Color(155, 89, 182, 190));
        g.fill(new Ellipse2D.Float(240, 120, 320, 220));
        g.dispose();
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static BufferedImage createAlphaMaskImage() {
        BufferedImage image = new BufferedImage(420, 280, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (int) (255.0 * x / (image.getWidth() - 1));
                int red = 255;
                int green = (int) (140.0 * y / (image.getHeight() - 1));
                int blue = 80;
                int rgba = (alpha << 24) | (red << 16) | (green << 8) | blue;
                image.setRGB(x, y, rgba);
            }
        }
        return image;
    }

    private static Path createZipAttachment(Path path) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            ZipEntry entry = new ZipEntry("readme.txt");
            zip.putNextEntry(entry);
            zip.write("PDF attachment payload for tests.\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            entry = new ZipEntry("meta/info.json");
            zip.putNextEntry(entry);
            zip.write("{\"source\":\"TestPdfGenerator\",\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private static Path createAttachmentPdf(Path path, Path bodyFontPath) throws IOException {
        try (PDDocument attachmentDoc = new PDDocument()) {
            PDPage p = new PDPage(PDRectangle.A6);
            attachmentDoc.addPage(p);
            PDFont bodyFont = loadRequiredEmbeddedFont(attachmentDoc, bodyFontPath, "AttachmentBodyFont");
            attachmentDoc.setDocumentInformation(createDocumentInformation(
                "PDFalyzer Embedded Attachment",
                "PDFalyzer",
                "Attachment payload generated for PDF embedding tests",
                "pdfalyzer,test,attachment,pdf",
                ZonedDateTime.now()
            ));
            try (PDPageContentStream cs = new PDPageContentStream(attachmentDoc, p)) {
                drawTextLine(cs, bodyFont, 12, 24, 380,
                        "Embedded Attachment PDF");
                drawTextLine(cs, bodyFont, 10, 24, 360,
                        "Created by TestPdfGenerator");
            }
            attachmentDoc.save(path.toFile());
        }
        return path;
    }

    private static Path createAttachmentXls(Path path) throws IOException {
        String data = "Name\tValue\nalpha\t123\nbeta\t456\ngamma\t789\n";
        Files.write(path, data.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static Path createJp2Placeholder(Path target, Path sourceImage) throws IOException {
        byte[] bytes = Files.readAllBytes(sourceImage);
        Files.write(target, bytes);
        return target;
    }

    private static Path ensureFont(Path target, List<String> urls) {
        if (Files.exists(target)) {
            return target;
        }
        Path downloaded = ensureBinary(target, urls);
        if (downloaded != null && Files.exists(downloaded)) {
            String lower = target.getFileName().toString().toLowerCase();
            if (lower.endsWith(".ttf") || lower.endsWith(".otf")) {
                try {
                    byte[] head = Files.readAllBytes(downloaded);
                    if (!looksLikeOpenType(head)) {
                        Path extracted = extractFontFromZip(downloaded, target);
                        if (extracted != null) return extracted;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return downloaded;
    }

    private static Path ensureZipEntry(Path target, Path zipFile, String zipEntryName) {
        if (Files.exists(target)) return target;
        if (zipFile == null || !Files.exists(zipFile)) return null;
        try {
            return extractSpecificZipEntry(zipFile, zipEntryName, target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path ensureBinary(Path target, List<String> urls) {
        if (Files.exists(target)) {
            return target;
        }
        for (String url : urls) {
            try {
                if (download(url, target)) {
                    return target;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean download(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "pdfalyzer-test-generator")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 200 && response.statusCode() < 300 && response.body().length > 0) {
            Files.write(target, response.body());
            return true;
        }
        return false;
    }

    private static void copyIfPresent(Path source, Path target) {
        if (source == null || !Files.exists(source)) return;
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private static void mirrorDirectoryTree(Path sourceRoot, Path targetRoot) {
        if (sourceRoot == null || targetRoot == null || !Files.isDirectory(sourceRoot)) {
            return;
        }
        try (var stream = Files.walk(sourceRoot)) {
            stream
                .filter(Files::isRegularFile)
                .forEach(source -> {
                    try {
                        Path relative = sourceRoot.relativize(source);
                        Path target = targetRoot.resolve(relative);
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignored) {
                    }
                });
        } catch (Exception ignored) {
        }
    }

    private static boolean looksLikeOpenType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        int b2 = bytes[2] & 0xFF;
        int b3 = bytes[3] & 0xFF;
        if (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return true; // TTF
        if (b0 == 'O' && b1 == 'T' && b2 == 'T' && b3 == 'O') return true; // OTF
        if (b0 == 't' && b1 == 't' && b2 == 'c' && b3 == 'f') return true; // TTC
        return false;
    }

    private static Path extractFontFromZip(Path zipPath, Path target) {
        String targetName = target.getFileName().toString().toLowerCase();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = Paths.get(entry.getName()).getFileName().toString().toLowerCase();
                if (!name.equals(targetName)) continue;
                Files.copy(zin, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path extractSpecificZipEntry(Path zipPath, String zipEntryName, Path target) throws IOException {
        String wanted = zipEntryName.replace('\\', '/');
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (!entry.getName().replace('\\', '/').equals(wanted)) continue;
                Files.copy(zin, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
        }
        return null;
    }
}
