package io.pdfalyzer.tools;

import org.apache.pdfbox.cos.COSName;
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
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TestPdfGenerator {

    private static final float PAGE_MARGIN = 36f;

    public static void main(String[] args) throws Exception {
        Path outDir = args.length > 0
                ? Paths.get(args[0]).toAbsolutePath().normalize()
                : Paths.get("target", "generated-test-assets").toAbsolutePath().normalize();

        Files.createDirectories(outDir);

        Path fontsDir = outDir.resolve("fonts");
        Path imagesDir = outDir.resolve("images");
        Path attachmentsDir = outDir.resolve("attachments");
        Files.createDirectories(fontsDir);
        Files.createDirectories(imagesDir);
        Files.createDirectories(attachmentsDir);

        Path freeSans = ensureFont(fontsDir.resolve("FreeSans.ttf"),
                Arrays.asList(
                        "https://raw.githubusercontent.com/gnu-mirror-unofficial/freefont/master/freefont-ttf/FreeSans.ttf",
                        "https://ftp.gnu.org/gnu/freefont/freefont-ttf-20120503.zip"
                ));
        Path lato = ensureFont(fontsDir.resolve("Lato-Regular.ttf"),
                Arrays.asList("https://raw.githubusercontent.com/google/fonts/main/ofl/lato/Lato-Regular.ttf"));
        Path sourceSans = ensureFont(fontsDir.resolve("SourceSans3-Regular.otf"),
                Arrays.asList("https://raw.githubusercontent.com/adobe-fonts/source-sans/release/OTF/SourceSans3-Regular.otf"));

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
        Path pdfAttachment = createAttachmentPdf(attachmentsDir.resolve("sample-attachment.pdf"));
        Path xlsAttachment = createAttachmentXls(attachmentsDir.resolve("sample.xls"));

        Path outputPdf = outDir.resolve("test.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage(PDRectangle.LETTER);
            PDPage page2 = new PDPage(PDRectangle.LETTER);
            PDPage page3 = new PDPage(PDRectangle.LETTER);
            PDPage page4 = new PDPage(PDRectangle.LETTER);
            doc.addPage(page1);
            doc.addPage(page2);
            doc.addPage(page3);
            doc.addPage(page4);

            renderPage1Images(doc, page1, jpg, png, jp2);
            renderPage2Fonts(doc, page2, freeSans, lato, sourceSans);
            renderPage3LinksAndAttachments(doc, page3, page2, zipAttachment, pdfAttachment, xlsAttachment);
            renderPage4Forms(doc, page4);
            addBookmarks(doc, page1, page2, page3, page4);
            applyDocumentMetadata(doc);

            doc.save(outputPdf.toFile());
        }

        System.out.println("Generated PDF: " + outputPdf);
        System.out.println("Assets folder : " + outDir);
    }

    private static void renderPage1Images(PDDocument doc, PDPage page, Path jpg, Path png, Path jp2) throws IOException {
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
            writeHeading(cs, "Page 1: Images + Transparency / SMask", page.getMediaBox().getHeight() - 36);

            cs.drawImage(jpgImage, PAGE_MARGIN, 500, 230, 160);
            drawCaption(cs, PAGE_MARGIN, 488, "JPEG image (opaque)");

            cs.drawImage(pngImage, PAGE_MARGIN + 250, 500, 230, 160);
            drawCaption(cs, PAGE_MARGIN + 250, 488, "PNG image (alpha transparency)");

            cs.drawImage(alphaImage, PAGE_MARGIN, 280, 230, 160);
            drawCaption(cs, PAGE_MARGIN, 268, "Lossless ARGB image (soft mask)");

            if (jp2Image != null) {
                cs.drawImage(jp2Image, PAGE_MARGIN + 250, 280, 230, 160);
                drawCaption(cs, PAGE_MARGIN + 250, 268, "JPEG2000 image (JPX decode)");
            } else {
                drawBox(cs, PAGE_MARGIN + 250, 280, 230, 160);
                drawCaption(cs, PAGE_MARGIN + 258, 350, "JPEG2000 test asset present, renderer unavailable on this JVM");
                drawCaption(cs, PAGE_MARGIN + 258, 333, "(see images/sample-jpeg2000.jp2)");
            }
        }
    }

    private static void renderPage2Fonts(PDDocument doc, PDPage page, Path freeSans, Path lato, Path sourceSans) throws IOException {
        PDFont helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont freeSansFont = loadEmbeddedFontOrFallback(doc, freeSans, helvetica);
        PDFont latoFont = loadEmbeddedFontOrFallback(doc, lato, helvetica);
        PDFont sourceSansFont = loadEmbeddedFontOrFallback(doc, sourceSans, helvetica);

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            writeHeading(cs, "Page 2: Fonts (embedded + non-embedded)", page.getMediaBox().getHeight() - 36);

            drawTextLine(cs, helvetica, 13, PAGE_MARGIN, 700,
                    "Non-embedded Standard 14 font: Helvetica (built-in)");
            drawTextLine(cs, helvetica, 11, PAGE_MARGIN, 682,
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz 0123456789");

            drawTextLine(cs, freeSansFont, 15, PAGE_MARGIN, 640,
                    "Embedded TTF: FreeSans.ttf");
            drawTextLine(cs, freeSansFont, 12, PAGE_MARGIN, 622,
                    "The quick brown fox jumps over the lazy dog. 1234567890");

            drawTextLine(cs, latoFont, 15, PAGE_MARGIN, 580,
                    "Embedded TTF: Lato-Regular.ttf");
            drawTextLine(cs, latoFont, 12, PAGE_MARGIN, 562,
                    "Sphinx of black quartz, judge my vow. ÀÉÍÕÜ ñ ç");

            drawTextLine(cs, sourceSansFont, 15, PAGE_MARGIN, 520,
                    "Embedded OTF: SourceSans3-Regular.otf");
            drawTextLine(cs, sourceSansFont, 12, PAGE_MARGIN, 502,
                    "Pack my box with five dozen liquor jugs. € £ ¥");

            drawTextLine(cs, helvetica, 10, PAGE_MARGIN, 460,
                    "Font assets are stored in /fonts next to the generated PDF.");
        }
    }

    private static void renderPage3LinksAndAttachments(
            PDDocument doc,
            PDPage page,
            PDPage jumpTargetPage,
            Path zipAttachment,
            Path pdfAttachment,
            Path xlsAttachment
    ) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            writeHeading(cs, "Page 3: Links, Bookmarks, Attachments", page.getMediaBox().getHeight() - 36);
            drawTextLine(cs, new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12, PAGE_MARGIN, 700,
                    "External link: https://www.example.com");
            drawTextLine(cs, new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12, PAGE_MARGIN, 670,
                    "Internal link: jump to Page 2");
            drawTextLine(cs, new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12, PAGE_MARGIN, 640,
                    "Embedded attachments: sample.zip, sample-attachment.pdf, sample.xls");
        }

        addExternalLink(page, PAGE_MARGIN, 694, 240, 16, "https://www.example.com");
        addInternalLink(page, PAGE_MARGIN, 664, 180, 16, jumpTargetPage);

        Map<String, PDComplexFileSpecification> embedded = new LinkedHashMap<>();
        embedded.put("sample.zip", buildEmbeddedFile(doc, zipAttachment, "application/zip"));
        embedded.put("sample-attachment.pdf", buildEmbeddedFile(doc, pdfAttachment, "application/pdf"));
        embedded.put("sample.xls", buildEmbeddedFile(doc, xlsAttachment, "application/vnd.ms-excel"));

        PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
        PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
        tree.setNames(embedded);
        names.setEmbeddedFiles(tree);
        doc.getDocumentCatalog().setNames(names);

        addAttachmentAnnotation(page, embedded.get("sample.zip"), PAGE_MARGIN, 600);
        addAttachmentAnnotation(page, embedded.get("sample-attachment.pdf"), PAGE_MARGIN + 20, 600);
        addAttachmentAnnotation(page, embedded.get("sample.xls"), PAGE_MARGIN + 40, 600);
    }

    private static void renderPage4Forms(PDDocument doc, PDPage page) throws IOException {
        PDFont helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            writeHeading(cs, "Page 4: AcroForm Fields", page.getMediaBox().getHeight() - 36);
            drawTextLine(cs, helvetica, 12, PAGE_MARGIN, 720, "Text (required), text (readonly), multiline text");
            drawTextLine(cs, helvetica, 12, PAGE_MARGIN, 680, "Checkbox, radio, combo box, list box, signature field");

            drawTextLine(cs, helvetica, 10, PAGE_MARGIN, 646, "Name (required):");
            drawTextLine(cs, helvetica, 10, PAGE_MARGIN, 616, "Account (readonly):");
            drawTextLine(cs, helvetica, 10, PAGE_MARGIN, 586, "Notes (multiline):");
            drawTextLine(cs, helvetica, 10, PAGE_MARGIN, 496, "Agree checkbox:");
            drawTextLine(cs, helvetica, 10, PAGE_MARGIN, 466, "Radio option:");
            drawTextLine(cs, helvetica, 10, PAGE_MARGIN, 436, "Combo (country):");
            drawTextLine(cs, helvetica, 10, PAGE_MARGIN, 406, "List (priority):");
            drawTextLine(cs, helvetica, 10, PAGE_MARGIN, 348, "Signature:");
        }

        PDAcroForm form = new PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(form);
        form.setNeedAppearances(true);

        PDResources resources = new PDResources();
        COSName fontName = resources.add(helvetica);
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

        PDSignatureField signature = new PDSignatureField(form);
        signature.setPartialName("signature_field");
        signature.setRequired(true);
        addWidget(signature.getWidgets().get(0), page, 160, 330, 250, 26);
        fields.add(signature);
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
        byte[] data = Files.readAllBytes(file);
        PDEmbeddedFile embeddedFile = new PDEmbeddedFile(doc, new ByteArrayInputStream(data));
        embeddedFile.setSubtype(mimeType);
        embeddedFile.setSize(data.length);

        PDComplexFileSpecification spec = new PDComplexFileSpecification();
        spec.setFile(file.getFileName().toString());
        spec.setEmbeddedFile(embeddedFile);
        return spec;
    }

    private static PDFont loadEmbeddedFontOrFallback(PDDocument doc, Path fontPath, PDFont fallback) {
        if (fontPath == null || !Files.exists(fontPath)) {
            return fallback;
        }
        try {
            return PDType0Font.load(doc, fontPath.toFile());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static void writeHeading(PDPageContentStream cs, String text, float y) throws IOException {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
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

    private static void drawCaption(PDPageContentStream cs, float x, float y, String text) throws IOException {
        drawTextLine(cs, new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10, x, y, text);
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

    private static Path createAttachmentPdf(Path path) throws IOException {
        try (PDDocument attachmentDoc = new PDDocument()) {
            PDPage p = new PDPage(PDRectangle.A6);
            attachmentDoc.addPage(p);
            attachmentDoc.setDocumentInformation(createDocumentInformation(
                "PDFalyzer Embedded Attachment",
                "PDFalyzer",
                "Attachment payload generated for PDF embedding tests",
                "pdfalyzer,test,attachment,pdf",
                ZonedDateTime.now()
            ));
            try (PDPageContentStream cs = new PDPageContentStream(attachmentDoc, p)) {
                drawTextLine(cs, new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12, 24, 380,
                        "Embedded Attachment PDF");
                drawTextLine(cs, new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10, 24, 360,
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
        return ensureBinary(target, urls);
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
}
