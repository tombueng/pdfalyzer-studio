package io.pdfalyzer.tools;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.form.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Standalone generator for form-fields-showcase.pdf — a comprehensive, nicely formatted
 * PDF demonstrating every interesting AcroForm variant including unusual edge cases found
 * in real-world PDFs that most parsing tools cannot cope with.
 *
 * Run: mvn test-compile && mvn exec:java -Dexec.mainClass=io.pdfalyzer.tools.FormFieldShowcasePdfGenerator
 * Or as a JUnit test via FormFieldShowcasePdfGeneratorTest.
 */
public class FormFieldShowcasePdfGenerator {

    // ─── Page geometry ──────────────────────────────────────────────────────────
    static final float PW     = 612f;
    static final float PH     = 792f;
    static final float MARGIN = 36f;
    static final float CW     = PW - 2 * MARGIN;    // usable content width = 540

    // ─── Box layout ─────────────────────────────────────────────────────────────
    static final float TITLE_H   = 22f;   // title bar height
    static final float WZ_PAD    = 9f;    // padding above/below widget in its zone
    static final float EXPL_PAD  = 7f;    // padding around description text
    static final float BOX_GAP   = 9f;    // vertical gap between boxes
    static final float DESC_FS   = 7.5f;  // description font size
    static final float DESC_LH   = DESC_FS * 1.45f; // description line height

    // ─── Colours (R G B 0-1) ────────────────────────────────────────────────────
    static final float[] NAVY      = {0.095f, 0.157f, 0.275f};  // #182847 header bar
    static final float[] BLUE      = {0.176f, 0.298f, 0.545f};  // #2D4C8B border / accent
    static final float[] BLUE_LT   = {0.294f, 0.451f, 0.737f};  // #4B73BC lighter accent
    static final float[] LIGHT_BG  = {0.937f, 0.953f, 0.984f};  // #EFF3FB box fill
    static final float[] WHITE     = {1f, 1f, 1f};
    static final float[] DARK_TXT  = {0.12f, 0.12f, 0.15f};     // near-black body text
    static final float[] ORANGE    = {0.85f, 0.42f, 0.0f};      // warning text
    static final float[] GREEN     = {0.08f, 0.52f, 0.22f};     // "correct" note text
    static final float[] MID_GRAY  = {0.55f, 0.55f, 0.60f};

    // ─── Cell theme palettes for 2×3 variant grids (Blue/Green/Amber/Red/Gray/Purple) ───
    static final float[] CELL_BG_BLUE   = {0.92f, 0.95f, 0.99f};
    static final float[] CELL_BG_GREEN  = {0.90f, 0.97f, 0.91f};
    static final float[] CELL_BG_AMBER  = {0.99f, 0.96f, 0.86f};
    static final float[] CELL_BG_RED    = {0.99f, 0.92f, 0.92f};
    static final float[] CELL_BG_GRAY   = {0.93f, 0.93f, 0.94f};
    static final float[] CELL_BG_PURPLE = {0.95f, 0.90f, 0.99f};

    static final float[] CELL_BC_BLUE   = {0.176f, 0.298f, 0.545f};
    static final float[] CELL_BC_GREEN  = {0.08f,  0.52f,  0.22f};
    static final float[] CELL_BC_AMBER  = {0.72f,  0.45f,  0.02f};
    static final float[] CELL_BC_RED    = {0.70f,  0.12f,  0.12f};
    static final float[] CELL_BC_GRAY   = {0.40f,  0.40f,  0.45f};
    static final float[] CELL_BC_PURPLE = {0.45f,  0.12f,  0.65f};

    // Parallel arrays — index 0..5 maps to Blue/Green/Amber/Red/Gray/Purple
    static final float[][] CELL_BG_THEMES = {CELL_BG_BLUE, CELL_BG_GREEN, CELL_BG_AMBER, CELL_BG_RED, CELL_BG_GRAY, CELL_BG_PURPLE};
    static final float[][] CELL_BC_THEMES = {CELL_BC_BLUE, CELL_BC_GREEN, CELL_BC_AMBER, CELL_BC_RED, CELL_BC_GRAY, CELL_BC_PURPLE};

    // ─── Grid layout constants ───────────────────────────────────────────────────
    static final float GRID_COL_GAP  = 10f;  // horizontal gap between the two columns
    static final float GRID_ROW_GAP  = 8f;   // vertical gap between rows
    static final float CELL_HDR_H    = 14f;  // mini cell title-bar height
    static final float CELL_WGT_PAD  = 6f;   // top/bottom padding around widget in cell
    static final float GRID_OUTER_PAD = 8f;  // padding inside outer box above/below grid

    // ─── Global state (one-shot generator, not thread-safe) ─────────────────────
    static PDDocument             doc;
    static PDAcroForm             acroForm;
    static PDPage                 currentPage;
    static PDPageContentStream    cs;
    static float                  cursorY;
    static PDFont                 bodyFont;
    static String                 fontResName; // resource key in AcroForm DR, e.g. "F1"
    static int                    fieldIdx = 0; // unique field-name counter
    static Path                   fontPath;     // path to FreeSans TTF — used for re-loading font variants

    // ════════════════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ════════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        Path outPath  = resolveOutputPath(args);
        fontPath      = Paths.get("src/test/resources/fonts/FreeSans.ttf");
        Path iconPath = Paths.get("src/main/resources/static/icon.png");

        Files.createDirectories(outPath.getParent());

        doc      = new PDDocument();
        acroForm = new PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(acroForm);
        acroForm.setNeedAppearances(true); // viewers regenerate AP streams if needed

        // Embed FreeSans as the form's default font
        System.out.println("[1/11] Loading font: " + fontPath);
        bodyFont    = PDType0Font.load(doc, Files.newInputStream(fontPath), false);
        PDResources dr = new PDResources();
        COSName cosName = dr.add(bodyFont);
        fontResName = cosName.getName();
        acroForm.setDefaultResources(dr);
        acroForm.setDefaultAppearance("/" + fontResName + " 10 Tf 0 g");

        // ── Title page ──────────────────────────────────────────────────────────
        System.out.println("[2/11] Rendering title page...");
        startNewPage();
        renderTitlePage(iconPath);

        // ── Section: TEXT FIELDS ────────────────────────────────────────────────
        System.out.println("[3/11] Section: TEXT FIELDS");
        startNewPage();
        sectionHeader("TEXT FIELDS", "PDTextField — Standard & Unusual Variants");

        System.out.println("  tc_textFieldStyles"); tc_textFieldStyles();
        System.out.println("  tc_requiredText"); tc_requiredText();
        System.out.println("  tc_readonlyText"); tc_readonlyText();
        System.out.println("  tc_multilineScroll"); tc_multilineScroll();
        System.out.println("  tc_passwordField"); tc_passwordField();
        System.out.println("  tc_combField"); tc_combField();
        System.out.println("  tc_autoSizeFont"); tc_autoSizeFont();
        System.out.println("  tc_giantFont"); tc_giantFont();
        System.out.println("  tc_alignmentCenter"); tc_alignmentCenter();
        System.out.println("  tc_alignmentRight"); tc_alignmentRight();
        System.out.println("  tc_richText"); tc_richText();
        System.out.println("  tc_maxLenOnly"); tc_maxLenOnly();
        System.out.println("  tc_negativeQuadding"); tc_negativeQuadding();

        // ── Section: CHECKBOXES & RADIOS ────────────────────────────────────────
        System.out.println("[4/11] Section: CHECKBOXES & RADIO BUTTONS");
        sectionHeader("CHECKBOXES & RADIO BUTTONS", "PDCheckBox / PDRadioButton — Flag and Appearance Quirks");

        System.out.println("  tc_checkboxStyles"); tc_checkboxStyles();
        System.out.println("  tc_checkboxStandard"); tc_checkboxStandard();
        System.out.println("  tc_checkboxCustomOnState"); tc_checkboxCustomOnState();
        System.out.println("  tc_checkboxColoredBg"); tc_checkboxColoredBg();
        System.out.println("  tc_radioGroup"); tc_radioGroup();
        System.out.println("  tc_radioNoToggleToOff"); tc_radioNoToggleToOff();

        // ── Section: CHOICE FIELDS ──────────────────────────────────────────────
        System.out.println("[5/11] Section: COMBO BOXES & LIST BOXES");
        sectionHeader("COMBO BOXES & LIST BOXES", "PDComboBox / PDListBox — Edge Cases in Options and Selection");

        System.out.println("  tc_comboStyles"); tc_comboStyles();
        System.out.println("  tc_comboStandard"); tc_comboStandard();
        System.out.println("  tc_comboEditable"); tc_comboEditable();
        System.out.println("  tc_comboPairedOptions"); tc_comboPairedOptions();
        System.out.println("  tc_listMultiSelect"); tc_listMultiSelect();
        System.out.println("  tc_listTopIndex"); tc_listTopIndex();
        System.out.println("  tc_comboValueOutsideOpt"); tc_comboValueOutsideOpt();

        // ── Section: PUSH BUTTONS ───────────────────────────────────────────────
        System.out.println("[6/11] Section: PUSH BUTTONS");
        sectionHeader("PUSH BUTTONS", "PDPushButton — Actions, Print Flags, and Embedded Actions");

        System.out.println("  tc_buttonStyles"); tc_buttonStyles();
        System.out.println("  tc_resetButton"); tc_resetButton();
        System.out.println("  tc_submitButton"); tc_submitButton();
        System.out.println("  tc_printOnlyButton"); tc_printOnlyButton();

        // ── Section: FONT EMBEDDING VARIANTS ────────────────────────────────────
        System.out.println("[7/11] Section: FONT EMBEDDING VARIANTS");
        sectionHeader("FONT EMBEDDING VARIANTS", "Embedded Full / Subset / Not-Embedded / Standard-14 — Portability, File Size, and Glyph Availability");

        System.out.println("  tc_fontVariants"); tc_fontVariants();
        System.out.println("  tc_checkboxFontVariants"); tc_checkboxFontVariants();
        System.out.println("  tc_textFieldValueSubset"); tc_textFieldValueSubset();

        // ── Section: WIDGET ANNOTATION QUIRKS ───────────────────────────────────
        System.out.println("[8/11] Section: WIDGET ANNOTATION BORDER STYLES");
        sectionHeader("WIDGET ANNOTATION BORDER STYLES", "Per-Widget /BS and /MK Dictionary — What Most Viewers Ignore");

        System.out.println("  tc_borderNone"); tc_borderNone();
        System.out.println("  tc_borderDashed"); tc_borderDashed();
        System.out.println("  tc_borderBeveled"); tc_borderBeveled();
        System.out.println("  tc_borderInset"); tc_borderInset();
        System.out.println("  tc_borderUnderline"); tc_borderUnderline();
        System.out.println("  tc_widgetRotated90"); tc_widgetRotated90();
        System.out.println("  tc_widgetMkColors"); tc_widgetMkColors();

        // ── Section: SIGNATURE FIELDS ───────────────────────────────────────────
        System.out.println("[9/11] Section: SIGNATURE FIELDS");
        sectionHeader("SIGNATURE FIELDS", "PDSignatureField — Empty, Lock Dict, and DocMDP Certification");

        System.out.println("  tc_signatureEmpty"); tc_signatureEmpty();
        System.out.println("  tc_signatureWithLock"); tc_signatureWithLock();
        System.out.println("  tc_signatureDocMdp"); tc_signatureDocMdp();

        // ── Section: EXTREME EDGE CASES ─────────────────────────────────────────
        System.out.println("[10/11] Section: EXTREME EDGE CASES");
        sectionHeader("EXTREME EDGE CASES", "Legal-but-Unusual PDF Structures That Break Most Parsers");

        System.out.println("  tc_hiddenWidget"); tc_hiddenWidget();
        System.out.println("  tc_zeroHeightWidget"); tc_zeroHeightWidget();
        System.out.println("  tc_veryLongValue"); tc_veryLongValue();
        System.out.println("  tc_hierarchicalName"); tc_hierarchicalName();
        System.out.println("  tc_missingDA"); tc_missingDA();
        System.out.println("  tc_dvVMismatch"); tc_dvVMismatch();

        // ── Finish ───────────────────────────────────────────────────────────────
        System.out.println("[11/11] Saving PDF...");
        cs.close();
        doc.save(outPath.toFile());
        doc.close();
        System.out.println("Generated: " + outPath.toAbsolutePath());
    }

    private static Path resolveOutputPath(String[] args) {
        return args.length > 0
            ? Paths.get(args[0]).toAbsolutePath().normalize()
            : Paths.get("src/main/resources/sample-pdfs/form-fields-showcase.pdf")
                   .toAbsolutePath().normalize();
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  PAGE MANAGEMENT
    // ════════════════════════════════════════════════════════════════════════════

    private static void startNewPage() throws IOException {
        if (cs != null) cs.close();
        currentPage = new PDPage(PDRectangle.LETTER);
        doc.addPage(currentPage);
        cs = new PDPageContentStream(doc, currentPage);
        cursorY = PH - MARGIN;
    }

    private static void ensureSpace(float needed) throws IOException {
        if (cursorY - needed < MARGIN + BOX_GAP) startNewPage();
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  TITLE PAGE
    // ════════════════════════════════════════════════════════════════════════════

    private static void renderTitlePage(Path iconPath) throws IOException {
        // ── Banner background ──────────────────────────────────────────────────
        float bannerH = 120f;
        float bannerY = PH - MARGIN - bannerH;
        setFill(NAVY);
        fillRect(MARGIN, bannerY, CW, bannerH);

        // ── Logo image (icon.png) ──────────────────────────────────────────────
        if (Files.exists(iconPath)) {
            PDImageXObject logo = PDImageXObject.createFromFileByContent(iconPath.toFile(), doc);
            cs.drawImage(logo, MARGIN + 8, bannerY + 20, 80, 80);
        } else {
            // Fallback: draw programmatic logo badge
            drawLogoBadge(MARGIN + 8, bannerY + 15, 90);
        }

        // ── Title text ─────────────────────────────────────────────────────────
        setFill(WHITE);
        drawText(bodyFont, 24f, MARGIN + 108, bannerY + 72, "PDFalyzer");
        setFill(BLUE_LT);
        drawText(bodyFont, 13f, MARGIN + 108, bannerY + 50, "AcroForm Field Showcase");
        setFill(MID_GRAY);
        drawText(bodyFont, 9f,  MARGIN + 108, bannerY + 34, "Comprehensive test document \u2014 every interesting variant, quirk, and edge case");
        drawText(bodyFont, 9f,  MARGIN + 108, bannerY + 20, "Each box shows a live form field above an explanation of what is unusual and what breaks.");

        // ── Coloured rule ──────────────────────────────────────────────────────
        setFill(BLUE);
        fillRect(MARGIN, bannerY - 4, CW, 4);

        cursorY = bannerY - 18;

        // ── About paragraph ────────────────────────────────────────────────────
        setFill(DARK_TXT);
        drawText(bodyFont, 10f, MARGIN, cursorY,
            "This PDF was generated by FormFieldShowcasePdfGenerator (Apache PDFBox 3.x). It contains " +
            "real AcroForm widgets");
        cursorY -= 13;
        drawText(bodyFont, 10f, MARGIN, cursorY,
            "with every interesting flag combination, unusual /DA string, border style, rotation, " +
            "and signature variant.");
        cursorY -= 13;
        drawText(bodyFont, 10f, MARGIN, cursorY,
            "Use it to stress-test PDF parsers, form renderers, and PDF editing tools.");
        cursorY -= 22;

        // ── Table of contents ──────────────────────────────────────────────────
        setFill(NAVY);
        fillRect(MARGIN, cursorY - 18, CW, 20);
        setFill(WHITE);
        drawText(bodyFont, 10f, MARGIN + 8, cursorY - 13, "Contents");
        cursorY -= 26;

        String[][] toc = {
            {"Text Fields",                "Required \u2022 Read-Only \u2022 Multiline \u2022 Password \u2022 Comb \u2022 Auto-Size Font \u2022 Giant Font \u2022 Alignment \u2022 Rich Text \u2022 MaxLen \u2022 Invalid Quadding"},
            {"Checkboxes & Radio Buttons", "Standard \u2022 Custom On-State \u2022 Coloured Background \u2022 Radio Group \u2022 NoToggleToOff"},
            {"Combo & List Boxes",         "Non-Editable \u2022 Editable \u2022 Paired Options \u2022 Multi-Select \u2022 TopIndex \u2022 Value Not in Opt"},
            {"Push Buttons",               "Reset Form \u2022 Submit Form \u2022 Print-Only (NoView)"},
            {"Widget Border Styles",       "No Border \u2022 Dashed \u2022 Beveled \u2022 Inset \u2022 Underline \u2022 90\u00b0 Rotation \u2022 MK Colours"},
            {"Signature Fields",           "Empty Placeholder \u2022 Lock Dictionary \u2022 DocMDP Certification"},
            {"Extreme Edge Cases",         "Hidden Widget \u2022 Zero-Height \u2022 1000-char Value \u2022 Hierarchical Name \u2022 Missing DA \u2022 V/DV Mismatch"},
        };

        boolean odd = true;
        for (String[] row : toc) {
            float rowH = 28f;
            ensureSpace(rowH);
            if (odd) {
                setFill(0.97f, 0.97f, 1.0f);
                fillRect(MARGIN, cursorY - rowH, CW, rowH);
            }
            setFill(BLUE);
            drawText(bodyFont, 9.5f, MARGIN + 8, cursorY - 11, row[0]);
            setFill(DARK_TXT);
            drawText(bodyFont, 8f,   MARGIN + 175, cursorY - 11, row[1]);
            setStroke(MID_GRAY);
            cs.setLineWidth(0.4f);
            cs.moveTo(MARGIN, cursorY - rowH);
            cs.lineTo(MARGIN + CW, cursorY - rowH);
            cs.stroke();
            cursorY -= rowH;
            odd = !odd;
        }

        // ── Font note ─────────────────────────────────────────────────────────
        cursorY -= 18;
        ensureSpace(30);
        setFill(MID_GRAY);
        drawText(bodyFont, 8f, MARGIN, cursorY,
            "Embedded font: FreeSans (GNU FreeFont). All AcroForm fields reference this font via the /DR /Font resource dictionary.");
        cursorY -= 11;
        drawText(bodyFont, 8f, MARGIN, cursorY,
            "Form /NeedAppearances = true. Open in Adobe Acrobat to regenerate appearances; some viewers may render fields differently.");
    }

    /** Draw a stylised hexagonal P-badge when icon.png is unavailable. */
    private static void drawLogoBadge(float x, float y, float size) throws IOException {
        // Hex background
        setFill(BLUE);
        cs.addRect(x, y, size, size);
        cs.fill();
        // "P" letter
        setFill(WHITE);
        drawText(bodyFont, size * 0.62f, x + size * 0.22f, y + size * 0.24f, "P");
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  BOX DRAWING
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Draw a nicely formatted test-case box.
     * Title bar (navy) on top, widget zone in the middle, description below.
     *
     * @param title      Section title rendered in the navy title bar
     * @param descLines  Description lines. Prefix with "!" for orange warning, "+" for green note.
     * @param widgetZoneH Height (pts) reserved for the form widget(s)
     * @return [widgetZoneLeft, widgetZoneBottom, widgetZoneWidth, widgetZoneHeight]
     *         Caller places widget annotations inside this rect on currentPage.
     */
    private static float[] drawBox(String title, String[] descLines, float widgetZoneH) throws IOException {
        float descBlockH = EXPL_PAD + descLines.length * DESC_LH + EXPL_PAD;
        float totalH     = TITLE_H + widgetZoneH + descBlockH;

        ensureSpace(totalH + BOX_GAP);

        float boxY   = cursorY - totalH;
        cursorY      = boxY - BOX_GAP;

        // Outer fill
        setFill(LIGHT_BG);
        fillRect(MARGIN, boxY, CW, totalH);

        // Outer border
        setStroke(BLUE);
        cs.setLineWidth(0.75f);
        strokeRect(MARGIN, boxY, CW, totalH);

        // Title bar fill
        float titleBarY = boxY + descBlockH + widgetZoneH;
        setFill(NAVY);
        fillRect(MARGIN, titleBarY, CW, TITLE_H);

        // Title text (white)
        setFill(WHITE);
        drawText(bodyFont, 9f, MARGIN + 6, titleBarY + 7, title);

        // Divider between widget zone and description
        float dividerY = boxY + descBlockH;
        setStroke(BLUE_LT);
        cs.setLineWidth(0.5f);
        cs.moveTo(MARGIN + 2, dividerY);
        cs.lineTo(MARGIN + CW - 2, dividerY);
        cs.stroke();

        // Description lines
        float textY = dividerY - EXPL_PAD - DESC_FS;
        for (String line : descLines) {
            if (line.startsWith("!")) {
                setFill(ORANGE);
                drawText(bodyFont, DESC_FS, MARGIN + EXPL_PAD, textY, line.substring(1));
                setFill(DARK_TXT);
            } else if (line.startsWith("+")) {
                setFill(GREEN);
                drawText(bodyFont, DESC_FS, MARGIN + EXPL_PAD, textY, line.substring(1));
                setFill(DARK_TXT);
            } else {
                setFill(DARK_TXT);
                drawText(bodyFont, DESC_FS, MARGIN + EXPL_PAD, textY, line);
            }
            textY -= DESC_LH;
        }

        float wzBot = boxY + descBlockH;
        return new float[]{MARGIN, wzBot, CW, widgetZoneH};
    }

    /**
     * Draw a 2-column × 3-row grid of mini field-cells inside one outer box.
     * Each cell gets a distinct colour theme (Blue/Green/Amber/Red/Gray/Purple).
     *
     * @param title       Title rendered in the outer navy title bar.
     * @param cellTitles  6 short labels for the coloured mini-bars (index 0 = top-left).
     * @param cellWidgetH Height (pts) reserved for the widget inside each cell.
     * @return float[6][4] — each [4] = {x, y, w, h} widget zone; caller places annotation there.
     */
    private static float[][] drawGrid2x3Box(String title, String[] cellTitles, float cellWidgetH) throws IOException {
        float cellH   = CELL_HDR_H + CELL_WGT_PAD + cellWidgetH + CELL_WGT_PAD;
        float colW    = (CW - GRID_COL_GAP) / 2f;
        float gridH   = 3 * cellH + 2 * GRID_ROW_GAP;
        float totalH  = TITLE_H + GRID_OUTER_PAD + gridH + GRID_OUTER_PAD;

        ensureSpace(totalH + BOX_GAP);

        float boxY  = cursorY - totalH;
        cursorY     = boxY - BOX_GAP;

        // Outer fill and border
        setFill(LIGHT_BG);
        fillRect(MARGIN, boxY, CW, totalH);
        setStroke(BLUE);
        cs.setLineWidth(0.75f);
        strokeRect(MARGIN, boxY, CW, totalH);

        // Title bar
        float titleBarY = boxY + GRID_OUTER_PAD + gridH + GRID_OUTER_PAD;
        setFill(NAVY);
        fillRect(MARGIN, titleBarY, CW, TITLE_H);
        setFill(WHITE);
        drawText(bodyFont, 9f, MARGIN + 6, titleBarY + 7, title);

        // 6 cells: row 0 = top visual row, row 2 = bottom visual row
        float[][] zones    = new float[6][4];
        float     gridBase = boxY + GRID_OUTER_PAD;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 2; col++) {
                int   idx   = row * 2 + col;
                float cellX = MARGIN + col * (colW + GRID_COL_GAP);
                // row 0 is visually top → highest Y in PDF coords
                float cellY = gridBase + (2 - row) * (cellH + GRID_ROW_GAP);

                float[] bg = CELL_BG_THEMES[idx];
                float[] bc = CELL_BC_THEMES[idx];

                // Cell background
                setFill(bg);
                fillRect(cellX, cellY, colW, cellH);

                // Cell border (theme colour)
                setStroke(bc);
                cs.setLineWidth(1.0f);
                strokeRect(cellX, cellY, colW, cellH);

                // Coloured mini header bar
                setFill(bc);
                fillRect(cellX, cellY + cellH - CELL_HDR_H, colW, CELL_HDR_H);
                setFill(WHITE);
                String label = (cellTitles != null && idx < cellTitles.length) ? cellTitles[idx] : "";
                drawText(bodyFont, 7f, cellX + 4, cellY + cellH - CELL_HDR_H + 4, label);

                // Widget zone (y = bottom of padding area, h = cellWidgetH)
                zones[idx] = new float[]{cellX, cellY + CELL_WGT_PAD, colW, cellWidgetH};
            }
        }
        return zones;
    }

    private static void sectionHeader(String name, String subtitle) throws IOException {
        float h = 32f;
        ensureSpace(h + BOX_GAP * 2);

        float y = cursorY - h;
        // Left accent bar
        setFill(BLUE);
        fillRect(MARGIN, y, 5, h);
        // Header fill
        setFill(0.88f, 0.92f, 0.98f);
        fillRect(MARGIN + 5, y, CW - 5, h);
        // Border bottom
        setStroke(BLUE);
        cs.setLineWidth(1f);
        cs.moveTo(MARGIN, y);
        cs.lineTo(MARGIN + CW, y);
        cs.stroke();

        setFill(NAVY);
        drawText(bodyFont, 12f, MARGIN + 12, y + 17, name);
        setFill(BLUE);
        drawText(bodyFont, 8f,  MARGIN + 12, y + 6,  subtitle);

        cursorY = y - BOX_GAP * 1.5f;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  ACROFORM HELPERS
    // ════════════════════════════════════════════════════════════════════════════

    private static String uid() { return "f" + (++fieldIdx); }

    private static PDAnnotationWidget mkWidget(float x, float y, float w, float h) throws IOException {
        PDAnnotationWidget widget = new PDAnnotationWidget();
        widget.setRectangle(new PDRectangle(x, y, w, h));
        widget.setPage(currentPage);
        currentPage.getAnnotations().add(widget);
        return widget;
    }

    /** Place a single widget centred vertically in a widget zone rect. */
    private static PDAnnotationWidget widgetInZone(float[] zone, float w, float wh) throws IOException {
        float cx = zone[0] + 10f;  // left-aligned with indent
        float cy = zone[1] + (zone[3] - wh) / 2f;
        return mkWidget(cx, cy, w, wh);
    }

    private static void setBorderStyle(PDAnnotationWidget widget, String style, float lineW) {
        COSDictionary bs = new COSDictionary();
        bs.setName(COSName.TYPE, "Border");
        bs.setName(COSName.S, style);
        bs.setFloat(COSName.W, lineW);
        widget.getCOSObject().setItem(COSName.BS, bs);
    }

    private static void setBorderStyleDashed(PDAnnotationWidget widget, float lineW, int on, int off) {
        COSDictionary bs = new COSDictionary();
        bs.setName(COSName.TYPE, "Border");
        bs.setName(COSName.S, "D");
        bs.setFloat(COSName.W, lineW);
        COSArray dash = new COSArray();
        dash.add(COSInteger.get(on));
        dash.add(COSInteger.get(off));
        bs.setItem(COSName.getPDFName("D"), dash);
        widget.getCOSObject().setItem(COSName.BS, bs);
    }

    private static void setMkColors(PDAnnotationWidget widget, float[] bg, float[] bc) {
        COSDictionary mk = new COSDictionary();
        if (bg != null) {
            COSArray bgArr = new COSArray();
            bgArr.add(new COSFloat(bg[0]));
            bgArr.add(new COSFloat(bg[1]));
            bgArr.add(new COSFloat(bg[2]));
            mk.setItem(COSName.getPDFName("BG"), bgArr);
        }
        if (bc != null) {
            COSArray bcArr = new COSArray();
            bcArr.add(new COSFloat(bc[0]));
            bcArr.add(new COSFloat(bc[1]));
            bcArr.add(new COSFloat(bc[2]));
            mk.setItem(COSName.getPDFName("BC"), bcArr);
        }
        widget.getCOSObject().setItem(COSName.MK, mk);
    }

    private static void setWidgetRotation(PDAnnotationWidget widget, int degrees) {
        COSDictionary mk = (COSDictionary) widget.getCOSObject()
            .getDictionaryObject(COSName.MK);
        if (mk == null) { mk = new COSDictionary(); widget.getCOSObject().setItem(COSName.MK, mk); }
        mk.setInt(COSName.getPDFName("R"), degrees);
    }

    private static void setAnnotationFlags(PDAnnotationWidget widget, int flags) {
        widget.getCOSObject().setInt(COSName.F, flags);
    }

    /**
     * Properly links a standalone widget annotation to a terminal field via a /Kids array.
     * PDTerminalField.getWidgets() returns a fresh ArrayList, so .set(0,w) is a no-op and
     * does NOT wire the widget to the field. This method sets /Parent on the widget and adds
     * it to the field's /Kids array so Adobe Reader (and other viewers) can link them.
     */
    private static void linkWidget(PDField field, PDAnnotationWidget widget) {
        widget.getCOSObject().setItem(COSName.PARENT, field.getCOSObject());
        COSArray kids = new COSArray();
        kids.add(widget.getCOSObject());
        field.getCOSObject().setItem(COSName.KIDS, kids);
    }

    private static void setJsAction(PDField field, String aaKey, String js) {
        COSDictionary aa = (COSDictionary) field.getCOSObject()
            .getDictionaryObject(COSName.AA);
        if (aa == null) { aa = new COSDictionary(); field.getCOSObject().setItem(COSName.AA, aa); }
        COSDictionary action = new COSDictionary();
        action.setName(COSName.S, "JavaScript");
        action.setString(COSName.JS, js);
        aa.setItem(COSName.getPDFName(aaKey), action);
    }

    private static void addField(PDField field) {
        acroForm.getFields().add(field);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  VISUAL STYLE VARIANTS  (2-col × 3-row grids, one per major section)
    // ════════════════════════════════════════════════════════════════════════════

    private static void tc_textFieldStyles() throws IOException {
        float[][] zones = drawGrid2x3Box(
            "TEXT FIELDS \u2014 6 Visual Themes  (Plain / Green / Amber / Red / Gray / Purple)",
            new String[]{
                "Plain \u2014 no MK, no colour (baseline)",
                "Green \u2014 success state",
                "Amber \u2014 warning state",
                "Red \u2014 error / invalid",
                "Gray \u2014 disabled / read-only",
                "Purple \u2014 custom accent",
            }, 20f);

        // i=0: completely plain — no MK colors, no custom DA colour (baseline)
        // i=1..5: Green / Amber / Red / Gray / Purple themes
        String[]  values   = {"Plain default (no styling)", "Valid entry [OK]", "Check this value!", "Invalid input [ERR]", "Read-only value", "Custom accent"};
        String[]  daColors = {"0 g", "0.05 0.45 0.15 rg", "0.55 0.30 0 rg", "0.55 0.05 0.05 rg", "0.35 0.35 0.38 rg", "0.35 0.08 0.55 rg"};
        String[]  bsStyles = {"S", "S", "S", "S", "S", "S"};
        float[]   bsWidths = {1.0f, 1.5f, 1.5f, 2.0f, 0.75f, 1.5f};

        for (int i = 0; i < 6; i++) {
            PDTextField f = new PDTextField(acroForm);
            f.setPartialName(uid());
            f.setDefaultAppearance("/" + fontResName + " 10 Tf " + daColors[i]);
            float ww = zones[i][2] - 16f;
            float wx = zones[i][0] + 8f;
            float wy = zones[i][1] + (zones[i][3] - 16f) / 2f;
            PDAnnotationWidget w = mkWidget(wx, wy, ww, 16f);
            linkWidget(f, w);
            setBorderStyle(w, bsStyles[i], bsWidths[i]);
            if (i > 0) setMkColors(w, CELL_BG_THEMES[i], CELL_BC_THEMES[i]); // i=0 = plain, no MK
            if (i == 4) f.setReadOnly(true);
            f.setValue(values[i]);
            addField(f);
        }
    }

    private static void tc_checkboxStyles() throws IOException {
        float[][] zones = drawGrid2x3Box(
            "CHECKBOXES \u2014 6 Visual Themes  (Plain / Green / Amber / Red / Gray / Purple)",
            new String[]{
                "Plain \u2014 no MK, no colour (baseline)",
                "Green \u2014 checked",
                "Amber \u2014 unchecked",
                "Red \u2014 checked",
                "Gray \u2014 unchecked (disabled)",
                "Purple \u2014 checked",
            }, 18f);

        // i=0: plain default — no MK colors; i=1..5 themed
        boolean[] checked = {true, true, false, true, false, true};

        for (int i = 0; i < 6; i++) {
            PDCheckBox f = new PDCheckBox(acroForm);
            f.setPartialName(uid());
            float cx = zones[i][0] + 8f;
            float cy = zones[i][1] + (zones[i][3] - 14f) / 2f;
            PDAnnotationWidget w = mkWidget(cx, cy, 14f, 14f);
            linkWidget(f, w);
            if (i > 0) setMkColors(w, CELL_BG_THEMES[i], CELL_BC_THEMES[i]); // i=0 = plain, no MK
            if (i > 0) setBorderStyle(w, "S", 1.5f);
            if (i == 4) f.setReadOnly(true);
            addField(f);
            if (checked[i]) {
                try { f.check(); } catch (Exception ignored) {}
            }
            // Label next to the box
            setFill(i > 0 ? CELL_BC_THEMES[i] : DARK_TXT);
            drawText(bodyFont, 8f, cx + 18f, cy + 2f, checked[i] ? "Checked" : "Unchecked");
        }
    }

    private static void tc_comboStyles() throws IOException {
        float[][] zones = drawGrid2x3Box(
            "COMBO BOXES \u2014 6 Visual Themes  (Plain / thick / dashed / beveled / inset / underline)",
            new String[]{
                "Plain \u2014 no MK, no colour (baseline)",
                "Green \u2014 solid, 2.5 pt",
                "Amber \u2014 dashed [4,2]",
                "Red \u2014 beveled (/B)",
                "Gray \u2014 inset (/I)",
                "Purple \u2014 underline (/U)",
            }, 18f);

        // i=0: plain default — no MK colors, no explicit border style
        // i=1..5: Green thick / Amber dashed / Red beveled / Gray inset / Purple underline
        String[] selected  = {"Option A", "Option B", "Option C", "Option D", "Option E", "Option F"};
        // [borderStyle, lineWidth] for each cell; index 0 is unused (plain)
        String[][] bsCfg   = {{"S","1.0"}, {"S","2.5"}, {"D","2.0"}, {"B","2.0"}, {"I","2.0"}, {"U","2.0"}};

        for (int i = 0; i < 6; i++) {
            PDComboBox f = new PDComboBox(acroForm);
            f.setPartialName(uid());
            f.setOptions(Arrays.asList("Option A", "Option B", "Option C", "Option D", "Option E", "Option F"));
            f.setDefaultAppearance("/" + fontResName + " 9 Tf 0 g");
            float ww = zones[i][2] - 16f;
            float wx = zones[i][0] + 8f;
            float wy = zones[i][1] + (zones[i][3] - 16f) / 2f;
            PDAnnotationWidget w = mkWidget(wx, wy, ww, 16f);
            linkWidget(f, w);
            if (i > 0) {                                     // i=0 = plain, no MK, no custom border
                setMkColors(w, CELL_BG_THEMES[i], CELL_BC_THEMES[i]);
                if ("D".equals(bsCfg[i][0])) {
                    setBorderStyleDashed(w, Float.parseFloat(bsCfg[i][1]), 4, 2);
                } else {
                    setBorderStyle(w, bsCfg[i][0], Float.parseFloat(bsCfg[i][1]));
                }
            }
            f.setValue(selected[i]);
            addField(f);
        }
    }

    private static void tc_buttonStyles() throws IOException {
        float[][] zones = drawGrid2x3Box(
            "PUSH BUTTONS \u2014 6 Visual Themes  (Plain / Green / Amber / Red / Gray / Purple)",
            new String[]{
                "Plain \u2014 no MK, no colour (baseline)",
                "Green \u2014 confirm / submit",
                "Amber \u2014 warning action",
                "Red \u2014 destructive action",
                "Gray \u2014 secondary / cancel",
                "Purple \u2014 special action",
            }, 22f);

        // i=0: plain default — no MK colors, viewer-default appearance, just a caption
        // i=1..5: solid-fill with theme colour as button background
        String[] captions = {"Default button", "Confirm", "Warning", "Delete!", "Cancel", "Special"};

        for (int i = 0; i < 6; i++) {
            PDPushButton f = new PDPushButton(acroForm);
            f.setPartialName(uid());
            // Plain cell keeps default (black) text; themed cells use white text on coloured bg
            String daColor = (i > 0) ? "1 1 1 rg" : "0 g";
            f.getCOSObject().setString(COSName.DA, "/" + fontResName + " 9 Tf " + daColor);
            float ww = zones[i][2] - 30f;
            float wx = zones[i][0] + 15f;
            float wy = zones[i][1] + (zones[i][3] - 18f) / 2f;
            PDAnnotationWidget w = mkWidget(wx, wy, ww, 18f);
            linkWidget(f, w);
            if (i > 0) {                                     // i=0 = plain, no MK
                setMkColors(w, CELL_BC_THEMES[i], CELL_BC_THEMES[i]);
                setBorderStyle(w, "S", 1.0f);
            }
            COSDictionary mk = (COSDictionary) w.getCOSObject().getDictionaryObject(COSName.MK);
            if (mk == null) { mk = new COSDictionary(); w.getCOSObject().setItem(COSName.MK, mk); }
            mk.setString(COSName.getPDFName("CA"), captions[i]);
            addField(f);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  TEXT FIELD TEST CASES
    // ════════════════════════════════════════════════════════════════════════════

    private static void tc_requiredText() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Required Field  (Ff bit 2 = Required)",
            new String[]{
                "The Required flag (bit 2 of /Ff) marks a field as mandatory before form submission.",
                "Acrobat prevents submission when this field is empty; most parsers expose /Ff correctly.",
                "!Gotcha: some tools ignore Required at submission time and allow incomplete forms.",
                "+Here: value pre-filled; clearing it and submitting tests viewer enforcement.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setRequired(true);
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        f.setValue("Jane Doe  \u2190 required field");
        addField(f);
    }

    private static void tc_readonlyText() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Read-Only Field  (Ff bit 0 = ReadOnly)",
            new String[]{
                "The ReadOnly flag (bit 0 of /Ff) prevents user editing in the viewer.",
                "The /V value is still fully readable by parsers and included in exported form data.",
                "!Gotcha: some tools treat read-only as 'skip during export', losing the value entirely.",
                "+Some form designers combine ReadOnly with a coloured background to signal locked content.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setReadOnly(true);
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0.4 0.4 0.4 rg");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        f.setValue("ACC-2026-0001  (read-only)");
        setMkColors(w, new float[]{0.92f, 0.92f, 0.95f}, new float[]{0.6f, 0.6f, 0.7f});
        addField(f);
    }

    private static void tc_multilineScroll() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Multiline with Scroll Overflow  (Ff bit 13 = Multiline)",
            new String[]{
                "With Multiline set, the field accepts newlines. If text overflows the rect, a scrollbar appears.",
                "The full text lives in /V; the AP stream captures only the lines visible at last save.",
                "!Parsing /V gives complete text; parsing the AP stream gives only the visible portion.",
                "+PDFBox reads /V correctly. AP-only parsers will silently truncate long multiline values.",
            }, 60f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setMultiline(true);
        f.setDefaultAppearance("/" + fontResName + " 9 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 320f, 54f);
        linkWidget(f, w);
        f.setValue("Line 1: Visible text.\nLine 2: Also visible.\nLine 3: May be scrolled out of view.\n"
            + "Line 4: Only in /V, not in the AP stream if viewport was too small.\n"
            + "Line 5: Many tools silently drop this line when parsing the appearance stream.");
        addField(f);
    }

    private static void tc_passwordField() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Password Field  (Ff bit 14 = Password)",
            new String[]{
                "The Password flag masks characters with bullets/asterisks in the viewer.",
                "!Critical: /V is stored in PLAIN TEXT in the PDF. The masking is display-only.",
                "!Beyond document-level encryption, there is NO additional secret for password fields.",
                "!Password + Comb simultaneously: spec says Comb is ignored when Password is set. Many generators set both.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        // Set Password flag manually via Ff
        int ff = f.getCOSObject().getInt(COSName.FF, 0);
        f.getCOSObject().setInt(COSName.FF, ff | (1 << 13)); // bit 14 (1-indexed) = bit 13 (0-indexed)
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 220f, 18f);
        linkWidget(f, w);
        f.setValue("s3cr3tP4ssw0rd"); // stored plain in /V
        addField(f);
    }

    private static void tc_combField() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Comb Layout  (Ff bit 24 = Comb  +  /MaxLen = 12)",
            new String[]{
                "Comb divides the widget rectangle into MaxLen equal cells, one character per cell.",
                "Spec requires BOTH the Comb bit AND /MaxLen to be present \u2014 if /MaxLen is absent the field is malformed.",
                "!MaxLen = 0 with Comb causes division-by-zero in some older libraries (iText 5, ReportLab).",
                "!Setting Multiline or Password alongside Comb: spec says Comb is silently ignored in those cases.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        // Comb = bit 25 (1-indexed) = 0x01000000 per ISO 32000 Table 228
        int ff = f.getCOSObject().getInt(COSName.FF, 0);
        f.getCOSObject().setInt(COSName.FF, ff | 0x01000000);
        f.setMaxLen(12);
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 300f, 20f);
        linkWidget(f, w);
        f.setValue("ABCDE12345XY");
        addField(f);
    }

    private static void tc_autoSizeFont() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Auto-Size Font  (/DA with font size 0)",
            new String[]{
                "Setting font size to 0 in the /DA string tells the viewer to auto-fit the text to the field rect.",
                "Spec: ISO 32000-1 \u00a712.7.3.3. Acrobat honours this for single-line fields.",
                "!PDFBox 3.x ignores size=0 and clips text to the BBox instead of shrinking the font.",
                "!pdf.js renders text at 0pt \u2014 effectively invisible \u2014 in this case.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        // Font size 0 = auto-size
        f.setDefaultAppearance("/" + fontResName + " 0 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 320f, 18f);
        linkWidget(f, w);
        f.setValue("This text should auto-size to fit the field rectangle");
        addField(f);
    }

    private static void tc_giantFont() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Oversized Font in /DA  (font size 72pt in a 20pt-tall field)",
            new String[]{
                "Using a font size larger than the widget height is legal per spec but visually clips.",
                "Acrobat clips the overflowing text to the field rect boundary.",
                "!PDFBox AP stream generation overflows the BBox \u2014 the generated appearance is technically invalid.",
                "!Useful as a crash test: any code that divides widget height by font size can get values < 1.",
            }, 30f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        // 72pt font in an 18pt-high widget
        f.setDefaultAppearance("/" + fontResName + " 72 Tf 0.1 0.1 0.5 rg");
        PDAnnotationWidget w = widgetInZone(z, 280f, 20f);
        linkWidget(f, w);
        f.setValue("GIANT");
        addField(f);
    }

    private static void tc_alignmentCenter() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Centre Alignment  (/Q = 1)",
            new String[]{
                "/Q (Quadding) controls text alignment: 0 = left (default), 1 = centre, 2 = right.",
                "PDFBox honours /Q for PDTextField. Combo box display text ignores /Q in PDFBox 3.x.",
                "+Quadding is inheritable: if absent on the field, inherited from the parent or from /AcroForm.",
                "!Flat parsers reading only the terminal field's /Q will miss inherited alignment.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setQ(1); // centre
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        f.setValue("Centred text value");
        addField(f);
    }

    private static void tc_alignmentRight() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Right Alignment  (/Q = 2)",
            new String[]{
                "Right-aligned text for numeric / currency input patterns.",
                "Required by many financial form designs; ensures numbers right-justify naturally.",
                "!Some viewers flip /Q to left-align when the field is in edit mode, reverting on blur.",
                "+Works correctly in Acrobat, pdf.js, and PDFBox setQ(2).",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setQ(2); // right
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        f.setValue("1.234,56");
        addField(f);
    }

    private static void tc_richText() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 RichText Field  (Ff bit 26 = RichText, /V + /RV)",
            new String[]{
                "With RichText flag (bit 26), /V holds plain-text fallback; /RV holds XHTML-style markup.",
                "Example /RV: <body><p style=\"font-weight:bold;font-size:14pt\">Bold</p></body>",
                "!PDFBox setValue() writes /V only \u2014 /RV must be set via raw COSDictionary access.",
                "!pdf.js ignores /RV entirely and falls back to /V. Acrobat/Reader render /RV markup.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        // Set RichText flag (bit 26, 0-indexed = 0x04000000)
        int ff = f.getCOSObject().getInt(COSName.FF, 0);
        f.getCOSObject().setInt(COSName.FF, ff | 0x04000000);
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 320f, 18f);
        linkWidget(f, w);
        f.setValue("Rich text fallback value");
        // Manually set /RV (rich value) via COS
        f.getCOSObject().setString(COSName.getPDFName("RV"),
            "<body xmlns=\"http://www.w3.org/1999/xhtml\"><p style=\"font-weight:bold;\">Rich text fallback value</p></body>");
        addField(f);
    }

    private static void tc_maxLenOnly() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 MaxLen Without Comb  (/MaxLen = 8, no Comb flag)",
            new String[]{
                "/MaxLen caps character input at 8 without the Comb cell visualisation.",
                "If /V already exceeds MaxLen (possible in hand-edited PDFs), Acrobat clips silently.",
                "!PDFBox getText() / getValue() returns the full stored /V regardless of MaxLen.",
                "!Some validators flag 'value exceeds MaxLen' as an error; spec says it's undefined behaviour.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setMaxLen(8);
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 220f, 18f);
        linkWidget(f, w);
        f.setValue("ABCDEFGHIJKLMNOP"); // 16 chars stored despite MaxLen=8
        addField(f);
    }

    private static void tc_negativeQuadding() throws IOException {
        float[] z = drawBox(
            "TEXT \u2014 Invalid Quadding Value  (/Q = \u22121)",
            new String[]{
                "/Q must be 0, 1, or 2 per spec. Negative values exist in wild-crafted and poorly-generated PDFs.",
                "Acrobat normalises /Q < 0 to 0 (left-align). PDFBox stores and returns the raw integer.",
                "!Some parsers crash or throw an assertion error when /Q is outside [0..2].",
                "!Tests robustness: valid enumerated-field validators must handle out-of-range cleanly.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.getCOSObject().setInt(COSName.Q, -1); // directly write invalid value
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        f.setValue("Negative /Q = -1");
        addField(f);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  CHECKBOX & RADIO BUTTON TEST CASES
    // ════════════════════════════════════════════════════════════════════════════

    private static void tc_checkboxStandard() throws IOException {
        float[] z = drawBox(
            "CHECKBOX \u2014 Standard Checked  (/AS = /Yes, off-state = /Off)",
            new String[]{
                "Standard checkbox: on-state named /Yes, off-state named /Off (both by convention, not spec mandate).",
                "PDFBox PDCheckBox.check() hardcodes looking for /Yes as the on-state name in the AP dictionary.",
                "!If the AP dict uses a non-/Yes on-state (e.g. /On, /Checked), PDBox sets /AS=/Yes but the",
                "!appearance won't switch \u2014 the field appears blank even though it is logically checked.",
            }, 28f);

        PDCheckBox f = new PDCheckBox(acroForm);
        f.setPartialName(uid());
        PDAnnotationWidget w = widgetInZone(z, 16f, 16f);
        linkWidget(f, w);
        addField(f);
        f.check();
    }

    private static void tc_checkboxCustomOnState() throws IOException {
        float[] z = drawBox(
            "CHECKBOX \u2014 Custom On-State Name  (/AS = /Checked, not /Yes)",
            new String[]{
                "The on-state AP stream key is /Checked (not the conventional /Yes).",
                "!PDFBox PDCheckBox.check() sets /AS = /Yes \u2014 but /Yes has no AP entry \u2014 field appears blank in Acrobat.",
                "!Your AcroFormTreeBuilder hardcodes the off-state check as '\"Off\".equalsIgnoreCase(current)'.",
                "!Any off-state named /Nein, /false, or /0 will be misidentified as 'checked' by that logic.",
            }, 30f);

        PDCheckBox f = new PDCheckBox(acroForm);
        f.setPartialName(uid());
        PDAnnotationWidget w = widgetInZone(z, 16f, 16f);
        linkWidget(f, w);

        // Build a minimal AP stream with /Checked instead of /Yes
        COSDictionary ap = new COSDictionary();
        COSDictionary n  = new COSDictionary();
        COSStream onStream  = doc.getDocument().createCOSStream();
        COSStream offStream = doc.getDocument().createCOSStream();
        try (OutputStream out = onStream.createOutputStream()) {
            out.write("q 0 0 1 rg 2 2 12 12 re f Q BT /Helv 10 Tf 2 3 Td (X) Tj ET".getBytes());
        }
        try (OutputStream out = offStream.createOutputStream()) {
            out.write("".getBytes());
        }
        n.setItem(COSName.getPDFName("Checked"), onStream);
        n.setItem(COSName.getPDFName("Off"), offStream);
        ap.setItem(COSName.N, n);
        w.getCOSObject().setItem(COSName.AP, ap);
        // Set current state to "Checked"
        w.getCOSObject().setName(COSName.AS, "Checked");

        addField(f);
    }

    private static void tc_checkboxColoredBg() throws IOException {
        float[] z = drawBox(
            "CHECKBOX \u2014 Coloured Widget Background  (/MK /BG + /BC)",
            new String[]{
                "/MK /BG [1 1 0] = yellow background; /MK /BC [0 0 0.8] = blue border colour.",
                "+PDFBox reads /MK /BG correctly via PDAppearanceCharacteristicsDictionary.getBackground().",
                "!PDFBox does NOT apply /BG when auto-generating AP streams. Background stays white.",
                "!You must manually draw the background fill in the AP content stream to honour /BG.",
            }, 28f);

        PDCheckBox f = new PDCheckBox(acroForm);
        f.setPartialName(uid());
        PDAnnotationWidget w = widgetInZone(z, 16f, 16f);
        linkWidget(f, w);
        setMkColors(w, new float[]{1f, 1f, 0f}, new float[]{0f, 0f, 0.8f});
        addField(f);
        f.check();
    }

    private static void tc_radioGroup() throws IOException {
        float[] z = drawBox(
            "RADIO \u2014 Standard 3-Option Group  (single PDRadioButton, 3 widgets)",
            new String[]{
                "A radio group is ONE PDRadioButton field with multiple widget annotations per page.",
                "Each widget represents one option via its AP /N subdictionary key (the export value name).",
                "The field /V holds the currently selected on-state name; all widgets share the same parent.",
                "!Parsers that call getWidgets().get(0) to find page/rect report only the first widget's position.",
            }, 28f);

        PDRadioButton f = new PDRadioButton(acroForm);
        f.setPartialName(uid());
        f.setExportValues(Arrays.asList("option_a", "option_b", "option_c"));

        // getWidgets() returns a snapshot, so add() on it doesn't persist — create widgets
        // directly and wire them into the field's COS Kids array instead.
        PDAnnotationWidget[] wArr = {
            new PDAnnotationWidget(), new PDAnnotationWidget(), new PDAnnotationWidget()
        };
        COSArray radioKids = new COSArray();
        for (PDAnnotationWidget ww : wArr) {
            ww.getCOSObject().setItem(COSName.PARENT, f.getCOSObject());
            radioKids.add(ww.getCOSObject());
        }
        f.getCOSObject().setItem(COSName.KIDS, radioKids);

        float wzL = z[0] + 10f, wzB = z[1] + (z[3] - 16f) / 2f;
        String[] labels = {"Option A", "Option B", "Option C"};
        for (int i = 0; i < 3; i++) {
            PDAnnotationWidget w = wArr[i];
            w.setRectangle(new PDRectangle(wzL + i * 130f, wzB, 16f, 16f));
            w.setPage(currentPage);
            currentPage.getAnnotations().add(w);
            setFill(DARK_TXT);
            drawText(bodyFont, 9f, wzL + i * 130f + 20f, wzB + 3, labels[i]);
        }
        f.setValue("option_b");
        addField(f);
    }

    private static void tc_radioNoToggleToOff() throws IOException {
        float[] z = drawBox(
            "RADIO \u2014 NoToggleToOff CLEARED  (Ff bit 15 clear = can deselect)",
            new String[]{
                "Normally clicking a checked radio again does nothing (NoToggleToOff is SET by default).",
                "When NoToggleToOff (bit 15) is CLEARED, clicking the current selection deselects it.",
                "!This produces a valid state where /V = /Off and all radios are unchecked simultaneously.",
                "!Most UI frameworks hardcode radio buttons as 'always one selected'; this breaks that assumption.",
            }, 28f);

        PDRadioButton f = new PDRadioButton(acroForm);
        f.setPartialName(uid());
        f.setExportValues(Arrays.asList("yes", "no"));
        // Clear NoToggleToOff (bit 15, 0-indexed = 0x00008000)
        int ff = f.getCOSObject().getInt(COSName.FF, 0);
        f.getCOSObject().setInt(COSName.FF, ff & ~0x00008000);

        // Same fix as tc_radioGroup: wire widgets via COS Kids directly
        PDAnnotationWidget[] wArr2 = {new PDAnnotationWidget(), new PDAnnotationWidget()};
        COSArray radioKids2 = new COSArray();
        for (PDAnnotationWidget ww : wArr2) {
            ww.getCOSObject().setItem(COSName.PARENT, f.getCOSObject());
            radioKids2.add(ww.getCOSObject());
        }
        f.getCOSObject().setItem(COSName.KIDS, radioKids2);

        float wzB = z[1] + (z[3] - 16f) / 2f;
        String[] labels = {"Yes (click again to deselect)", "No"};
        for (int i = 0; i < 2; i++) {
            PDAnnotationWidget w = wArr2[i];
            w.setRectangle(new PDRectangle(z[0] + 10f + i * 200f, wzB, 16f, 16f));
            w.setPage(currentPage);
            currentPage.getAnnotations().add(w);
            drawText(bodyFont, 9f, z[0] + 10f + i * 200f + 20f, wzB + 3, labels[i]);
        }
        f.setValue("yes");
        addField(f);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  COMBO BOX & LIST BOX TEST CASES
    // ════════════════════════════════════════════════════════════════════════════

    private static void tc_comboStandard() throws IOException {
        float[] z = drawBox(
            "COMBO \u2014 Standard Non-Editable  (/V must match an /Opt entry)",
            new String[]{
                "Standard combo: /V holds the selected export value; /Opt lists allowed values.",
                "Critical: if /V is not in /Opt (e.g. from a prior save), Acrobat shows a blank field.",
                "!PDFBox returns the stored /V regardless of whether it matches /Opt \u2014 no validation.",
                "+Use paired /Opt arrays for export-value \u2260 display-name (see next box).",
            }, 28f);

        PDComboBox f = new PDComboBox(acroForm);
        f.setPartialName(uid());
        f.setOptions(Arrays.asList("DE", "US", "FR", "JP", "GB", "AU"));
        f.setDefaultAppearance("/" + fontResName + " 10 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 180f, 18f);
        linkWidget(f, w);
        f.setValue("DE");
        addField(f);
    }

    private static void tc_comboEditable() throws IOException {
        float[] z = drawBox(
            "COMBO \u2014 Editable Combo  (Ff bit 19 = Edit, user can type custom values)",
            new String[]{
                "With the Edit flag set, the user can type a custom value not present in /Opt.",
                "The typed value goes into /V but is NOT added to /Opt \u2014 not available in the dropdown on reload.",
                "!Some validators incorrectly flag '/V not in /Opt' as an error on editable combos.",
                "!Acrobat accepts the custom value; pdf.js clears it on reload.",
            }, 28f);

        PDComboBox f = new PDComboBox(acroForm);
        f.setPartialName(uid());
        f.setEdit(true);
        f.setOptions(Arrays.asList("Standard", "Premium", "Enterprise"));
        f.setDefaultAppearance("/" + fontResName + " 10 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 200f, 18f);
        linkWidget(f, w);
        f.setValue("Custom Tier (not in list)");
        addField(f);
    }

    private static void tc_comboPairedOptions() throws IOException {
        float[] z = drawBox(
            "COMBO \u2014 Paired Options  (/Opt = [[export, display], ...])",
            new String[]{
                "/Opt can contain 2-element sub-arrays: [export_value, display_name].",
                "The display name is shown in the UI; the export value is stored in /V and submitted.",
                "!PDFBox getOptions() returns display names only \u2014 export values are silently dropped.",
                "!Round-tripping via the high-level API destroys the export/display pairing.",
            }, 28f);

        PDComboBox f = new PDComboBox(acroForm);
        f.setPartialName(uid());
        // Build paired /Opt manually via COS
        COSArray opt = new COSArray();
        String[][] pairs = {{"de", "Germany"}, {"us", "United States"}, {"fr", "France"}, {"jp", "Japan"}};
        for (String[] pair : pairs) {
            COSArray item = new COSArray();
            item.add(new COSString(pair[0]));
            item.add(new COSString(pair[1]));
            opt.add(item);
        }
        f.getCOSObject().setItem(COSName.OPT, opt);
        f.setDefaultAppearance("/" + fontResName + " 10 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 200f, 18f);
        linkWidget(f, w);
        f.getCOSObject().setString(COSName.V, "de"); // export value in /V
        addField(f);
    }

    private static void tc_listMultiSelect() throws IOException {
        float[] z = drawBox(
            "LIST \u2014 Multi-Select  (Ff bit 22 = MultiSelect, /V and /I are arrays)",
            new String[]{
                "MultiSelect flag: /V and /I become arrays holding multiple selected values and indices.",
                "/I = [0, 2] means items at index 0 and 2 are selected. /V = [Low, High] holds their export values.",
                "!PDFBox ListBox.getValue() handles multi-select; /I-without-/V or out-of-range /I gives wrong results.",
                "!Spec requires /I to be sorted ascending. Unsorted /I is common in wild PDFs; Acrobat accepts it.",
            }, 72f);

        PDListBox f = new PDListBox(acroForm);
        f.setPartialName(uid());
        // Set MultiSelect flag (bit 22 per ISO 32000 = bit 21, 0-indexed = 0x00200000)
        int ff = f.getCOSObject().getInt(COSName.FF, 0);
        f.getCOSObject().setInt(COSName.FF, ff | 0x00200000);
        f.setOptions(Arrays.asList("Low", "Medium", "High", "Critical", "Blocker"));
        f.setDefaultAppearance("/" + fontResName + " 10 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 160f, 60f);
        linkWidget(f, w);
        // Set multi-value /V and /I directly
        COSArray v = new COSArray(); v.add(new COSString("Low")); v.add(new COSString("High"));
        COSArray i = new COSArray(); i.add(COSInteger.get(0)); i.add(COSInteger.get(2));
        f.getCOSObject().setItem(COSName.V, v);
        f.getCOSObject().setItem(COSName.getPDFName("I"), i);
        addField(f);
    }

    private static void tc_listTopIndex() throws IOException {
        float[] z = drawBox(
            "LIST \u2014 TopIndex Pre-Scrolled  (/TI = 4, list opens at item 5)",
            new String[]{
                "/TI (TopIndex) records which item index is at the top of the visible scroll area.",
                "Here /TI = 4 means the list opens scrolled to item 5 ('Item 5') rather than item 1.",
                "!PDFBox ignores /TI entirely in both reading and writing.",
                "!Round-tripping a document through PDFBox destroys the saved scroll position.",
            }, 72f);

        PDListBox f = new PDListBox(acroForm);
        f.setPartialName(uid());
        List<String> opts = new ArrayList<>();
        for (int i = 1; i <= 12; i++) opts.add("Item " + i);
        f.setOptions(opts);
        f.setDefaultAppearance("/" + fontResName + " 10 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 160f, 60f);
        linkWidget(f, w);
        f.setValue("Item 6");
        f.getCOSObject().setInt(COSName.getPDFName("TI"), 4); // pre-scroll to item 5
        addField(f);
    }

    private static void tc_comboValueOutsideOpt() throws IOException {
        float[] z = drawBox(
            "COMBO \u2014 Current Value Not in /Opt  (/V = 'CustomEntry', absent from list)",
            new String[]{
                "The stored /V ('CustomEntry') is absent from the /Opt array.",
                "This occurs with editable combos after typing, or in hand-edited PDFs.",
                "!Acrobat shows a blank field and may flag a validation warning on submit.",
                "!PDFBox getValue() returns the stored /V string without any validation or warning.",
            }, 28f);

        PDComboBox f = new PDComboBox(acroForm);
        f.setPartialName(uid());
        f.setOptions(Arrays.asList("Alpha", "Beta", "Gamma"));
        f.setDefaultAppearance("/" + fontResName + " 10 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 200f, 18f);
        linkWidget(f, w);
        f.getCOSObject().setString(COSName.V, "CustomEntry"); // not in /Opt
        addField(f);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  PUSH BUTTON TEST CASES
    // ════════════════════════════════════════════════════════════════════════════

    private static void tc_resetButton() throws IOException {
        float[] z = drawBox(
            "BUTTON \u2014 Reset Form  (/A /S /ResetForm)",
            new String[]{
                "/ResetForm action resets all fields to their /DV (default value), or blank if /DV is absent.",
                "Optional /Fields array limits which fields are reset; absent = reset ALL fields.",
                "!PDFBox has no high-level PDActionResetForm class \u2014 requires raw COSDictionary construction.",
                "!Empty /Fields = [] resets nothing (a common generator bug). Absent /Fields = reset all.",
            }, 32f);

        PDPushButton f = new PDPushButton(acroForm);
        f.setPartialName(uid());
        f.getCOSObject().setString(COSName.DA, "/" + fontResName + " 10 Tf 1 1 1 rg");
        PDAnnotationWidget w = widgetInZone(z, 160f, 22f);
        linkWidget(f, w);
        setMkColors(w, new float[]{0.2f, 0.35f, 0.65f}, new float[]{0.1f, 0.2f, 0.5f});
        // Set caption via MK
        COSDictionary mk = (COSDictionary) w.getCOSObject().getDictionaryObject(COSName.MK);
        if (mk == null) { mk = new COSDictionary(); w.getCOSObject().setItem(COSName.MK, mk); }
        mk.setString(COSName.getPDFName("CA"), "Reset All Fields");
        // Build ResetForm action via raw COS
        COSDictionary action = new COSDictionary();
        action.setName(COSName.TYPE, "Action");
        action.setName(COSName.S, "ResetForm");
        w.getCOSObject().setItem(COSName.A, action);
        addField(f);
    }

    private static void tc_submitButton() throws IOException {
        float[] z = drawBox(
            "BUTTON \u2014 Submit Form  (/A /S /SubmitForm, Flags = 4 = HTML format)",
            new String[]{
                "/SubmitForm action. Flags bit 2 (value 4) = HTML form data format.",
                "Bit 0 = FDF (default, legacy); bit 1 = XFDF; bit 2 = HTML; bit 3 = include empty fields.",
                "!pdf.js ignores SubmitForm entirely. Modern servers reject legacy FDF submissions.",
                "!PDFBox has PDActionSubmitForm but no execution logic. Parsing the action works.",
            }, 32f);

        PDPushButton f = new PDPushButton(acroForm);
        f.setPartialName(uid());
        f.getCOSObject().setString(COSName.DA, "/" + fontResName + " 10 Tf 1 1 1 rg");
        PDAnnotationWidget w = widgetInZone(z, 160f, 22f);
        linkWidget(f, w);
        setMkColors(w, new float[]{0.15f, 0.52f, 0.28f}, new float[]{0.05f, 0.35f, 0.15f});
        COSDictionary mk = (COSDictionary) w.getCOSObject().getDictionaryObject(COSName.MK);
        if (mk == null) { mk = new COSDictionary(); w.getCOSObject().setItem(COSName.MK, mk); }
        mk.setString(COSName.getPDFName("CA"), "Submit (HTML)");
        // Build SubmitForm action
        COSDictionary url = new COSDictionary();
        url.setName(COSName.FS, "URL");
        url.setString(COSName.F, "https://example.com/pdf-submit");
        COSDictionary action = new COSDictionary();
        action.setName(COSName.TYPE, "Action");
        action.setName(COSName.S, "SubmitForm");
        action.setItem(COSName.F, url);
        action.setInt(COSName.getPDFName("Flags"), 4); // HTML format
        w.getCOSObject().setItem(COSName.A, action);
        addField(f);
    }

    private static void tc_printOnlyButton() throws IOException {
        float[] z = drawBox(
            "BUTTON \u2014 Print-Only Widget  (F = 36 = NoView | Print)",
            new String[]{
                "Annotation flags F = 36 = NoView (bit 6) | Print (bit 3): invisible on screen, prints normally.",
                "Used for print watermarks, invisible tracking fields, and printer-specific form elements.",
                "!PDFBox does NOT enforce annotation flags during rendering \u2014 this field will be VISIBLE on screen.",
                "!pdf.js also renders NoView fields visibly. Only Acrobat Reader correctly hides this.",
            }, 32f);

        PDPushButton f = new PDPushButton(acroForm);
        f.setPartialName(uid());
        f.getCOSObject().setString(COSName.DA, "/" + fontResName + " 9 Tf 0.4 0.1 0.1 rg");
        PDAnnotationWidget w = widgetInZone(z, 240f, 22f);
        linkWidget(f, w);
        setMkColors(w, new float[]{1f, 0.85f, 0.85f}, new float[]{0.7f, 0.1f, 0.1f});
        COSDictionary mk = (COSDictionary) w.getCOSObject().getDictionaryObject(COSName.MK);
        if (mk == null) { mk = new COSDictionary(); w.getCOSObject().setItem(COSName.MK, mk); }
        mk.setString(COSName.getPDFName("CA"), "PRINT ONLY (should be invisible on screen)");
        setAnnotationFlags(w, 36); // NoView=32 | Print=4
        addField(f);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  WIDGET ANNOTATION BORDER STYLE TEST CASES
    // ════════════════════════════════════════════════════════════════════════════

    private static void tc_borderNone() throws IOException {
        float[] z = drawBox(
            "WIDGET \u2014 Zero-Width Border  (/BS /W = 0)",
            new String[]{
                "Zero border width means no visible frame, but the annotation rect remains interaction-active.",
                "Some viewers treat /W = 0 as 'skip AP rendering entirely', making the field completely invisible.",
                "!Interactive hit-testing still works \u2014 the user can click and type in an invisible field.",
                "+Commonly used in form designs that draw their own field box in the page content stream.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        setBorderStyle(w, "S", 0f); // solid, width 0
        f.setValue("No visible border (W=0)");
        addField(f);
    }

    private static void tc_borderDashed() throws IOException {
        float[] z = drawBox(
            "WIDGET \u2014 Dashed Border  (/BS /S = /D, /D = [4 2])",
            new String[]{
                "Border style /D (dashed) with /D [4 2] = 4pt on, 2pt off.",
                "!pdf.js 3.x renders only /S (solid) and /U (underline) \u2014 dashed appears as solid.",
                "!PDFBox does not generate dashed AP streams \u2014 the /BS flag is written but the AP uses a plain rect stroke.",
                "!Only Acrobat Reader renders the actual dashed pattern from the /BS /D array.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        setBorderStyleDashed(w, 2f, 4, 2);
        f.setValue("Dashed border (4 on, 2 off)");
        addField(f);
    }

    private static void tc_borderBeveled() throws IOException {
        float[] z = drawBox(
            "WIDGET \u2014 Beveled Border  (/BS /S = /B) \u2014 3D raised appearance",
            new String[]{
                "Beveled style uses lighter/darker shades of the border colour to simulate a 3D raised button.",
                "!PDFBox does not generate beveled AP streams \u2014 the /BS /S = /B flag is written but rendered as solid.",
                "!pdf.js renders as solid. Only Adobe Acrobat shows the true 3D beveled effect.",
                "!The shade colours are derived from /MK /BC. If /BC is absent, the effect is undefined.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        setBorderStyle(w, "B", 2f);
        setMkColors(w, new float[]{0.95f, 0.95f, 1f}, new float[]{0.3f, 0.3f, 0.7f});
        f.setValue("Beveled border (3D raised)");
        addField(f);
    }

    private static void tc_borderInset() throws IOException {
        float[] z = drawBox(
            "WIDGET \u2014 Inset Border  (/BS /S = /I) \u2014 3D sunken appearance",
            new String[]{
                "Inset style is the inverse of Beveled \u2014 shading makes the field appear sunken into the page.",
                "Used for read-only or locked field visual distinction in sophisticated form layouts.",
                "!Like Beveled, PDFBox does not implement inset AP generation \u2014 renders as solid.",
                "!Acrobat renders correctly; all other major PDF viewers fall back to solid border.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        setBorderStyle(w, "I", 2f);
        setMkColors(w, new float[]{0.88f, 0.88f, 0.92f}, new float[]{0.3f, 0.3f, 0.5f});
        f.setValue("Inset border (3D sunken)");
        addField(f);
    }

    private static void tc_borderUnderline() throws IOException {
        float[] z = drawBox(
            "WIDGET \u2014 Underline Border  (/BS /S = /U) \u2014 bottom edge only",
            new String[]{
                "Underline style draws only the bottom edge of the annotation rect.",
                "Common in flat / minimal form designs; gives a clean 'input line' appearance.",
                "+pdf.js supports underline border natively. PDFBox writes the flag but no underline AP.",
                "!PDFBox AP stream uses a full rectangle stroke regardless of /S = /U.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        setBorderStyle(w, "U", 2f);
        setMkColors(w, null, new float[]{0f, 0f, 0.6f});
        f.setValue("Underline only border");
        addField(f);
    }

    private static void tc_widgetRotated90() throws IOException {
        float[] z = drawBox(
            "WIDGET \u2014 Content Rotated 90\u00b0  (/MK /R = 90)",
            new String[]{
                "/MK /R = 90 rotates the field content 90\u00b0 clockwise within its unchanged rectangle.",
                "The annotation rect stays the same; the text appears rotated inside the widget box.",
                "!PDFBox widget.getAppearanceCharacteristics().getRotation() reads /MK /R correctly.",
                "!PDFBox AP stream generation IGNORES /R \u2014 text appears unrotated despite the flag being set.",
            }, 40f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 10 Tf 0 g");
        // Taller widget to show the rotation effect
        float wzB = z[1] + (z[3] - 30f) / 2f;
        PDAnnotationWidget w = mkWidget(z[0] + 10f, wzB, 160f, 30f);
        linkWidget(f, w);
        setWidgetRotation(w, 90);
        f.setValue("Rotated 90 degrees");
        addField(f);
    }

    private static void tc_widgetMkColors() throws IOException {
        float[] z = drawBox(
            "WIDGET \u2014 Full /MK Colour Set  (/BG orange + /BC blue)",
            new String[]{
                "/MK /BG [1 0.6 0] = orange background; /MK /BC [0 0 0.7] = dark blue border.",
                "+PDFBox PDAppearanceCharacteristicsDictionary.getBackground() reads /BG correctly.",
                "!PDFBox does NOT apply /BG in auto-generated AP streams; background stays white.",
                "!To honour /BG you must manually draw the fill rect in the AP content stream.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 0 0 rg");
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        setMkColors(w, new float[]{1f, 0.6f, 0f}, new float[]{0f, 0f, 0.7f});
        setBorderStyle(w, "S", 2f);
        f.setValue("Custom /BG and /BC colours");
        addField(f);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  SIGNATURE FIELD TEST CASES
    // ════════════════════════════════════════════════════════════════════════════

    private static void tc_signatureEmpty() throws IOException {
        float[] z = drawBox(
            "SIGNATURE \u2014 Empty Placeholder  (no /V entry)",
            new String[]{
                "A signature field with no /V entry is an unsigned placeholder waiting for a signature.",
                "!Many parsers crash calling getSignature() on an empty field (NullPointerException).",
                "+PDFBox PDSignatureField.getSignature() returns null correctly for unsigned fields.",
                "!Downstream code that assumes getSignature() is non-null is a pervasive failure point.",
            }, 38f);

        PDSignatureField f = new PDSignatureField(acroForm);
        f.setPartialName(uid());
        float wzB = z[1] + (z[3] - 28f) / 2f;
        PDAnnotationWidget w = mkWidget(z[0] + 10f, wzB, 320f, 28f);
        linkWidget(f, w);
        setMkColors(w, new float[]{0.97f, 0.97f, 0.97f}, new float[]{0.3f, 0.3f, 0.5f});
        addField(f);
    }

    private static void tc_signatureWithLock() throws IOException {
        float[] z = drawBox(
            "SIGNATURE \u2014 With Lock Dictionary  (/Lock /Action /Include)",
            new String[]{
                "/Lock dict on a sig field specifies which fields are locked after signing.",
                "/Action = All | Include (lock listed) | Exclude (lock all except listed). /Fields = list.",
                "!PDFBox has no high-level SigFieldLock API \u2014 raw COSDictionary access only.",
                "!Acrobat enforces locks post-signing; all other viewers typically ignore /Lock entirely.",
            }, 38f);

        PDSignatureField f = new PDSignatureField(acroForm);
        f.setPartialName(uid());
        float wzB = z[1] + (z[3] - 28f) / 2f;
        PDAnnotationWidget w = mkWidget(z[0] + 10f, wzB, 320f, 28f);
        linkWidget(f, w);
        // Build /Lock dictionary manually
        COSDictionary lock = new COSDictionary();
        lock.setName(COSName.TYPE, "SigFieldLock");
        lock.setName(COSName.getPDFName("Action"), "Include");
        COSArray lockFields = new COSArray();
        lockFields.add(new COSString("name_required"));
        lockFields.add(new COSString("account_readonly"));
        lock.setItem(COSName.getPDFName("Fields"), lockFields);
        f.getCOSObject().setItem(COSName.getPDFName("Lock"), lock);
        addField(f);
    }

    private static void tc_signatureDocMdp() throws IOException {
        float[] z = drawBox(
            "SIGNATURE \u2014 DocMDP Certification  (/Reference /TransformMethod /DocMDP, /P = 2)",
            new String[]{
                "A certification (DocMDP) signature uses /Reference /TransformParams /P to restrict edits.",
                "/P 1 = no changes; /P 2 = form fill-in allowed; /P 3 = form fill + annotations allowed.",
                "!PDFBox signs without reading DocMDP constraints \u2014 document can be freely modified after.",
                "!Saving a DocMDP-certified PDF with PDDocument.save() (not incremental) invalidates all prior signatures.",
            }, 38f);

        PDSignatureField f = new PDSignatureField(acroForm);
        f.setPartialName(uid());
        float wzB = z[1] + (z[3] - 28f) / 2f;
        PDAnnotationWidget w = mkWidget(z[0] + 10f, wzB, 320f, 28f);
        linkWidget(f, w);
        // Attach DocMDP structure to the field (no actual signature value)
        COSDictionary transformParams = new COSDictionary();
        transformParams.setName(COSName.TYPE, "TransformParams");
        transformParams.setInt(COSName.getPDFName("P"), 2);
        transformParams.setString(COSName.getPDFName("V"), "1.2");

        COSDictionary sigRef = new COSDictionary();
        sigRef.setName(COSName.TYPE, "SigRef");
        sigRef.setName(COSName.getPDFName("TransformMethod"), "DocMDP");
        sigRef.setItem(COSName.getPDFName("TransformParams"), transformParams);

        COSArray ref = new COSArray();
        ref.add(sigRef);
        f.getCOSObject().setItem(COSName.getPDFName("Reference"), ref);
        addField(f);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  EXTREME EDGE CASES
    // ════════════════════════════════════════════════════════════════════════════

    private static void tc_hiddenWidget() throws IOException {
        float[] z = drawBox(
            "EDGE CASE \u2014 Hidden Widget  (annotation F = 2 = Hidden)",
            new String[]{
                "Annotation flag F = 2 (Hidden) hides the widget completely \u2014 not displayed, not printed.",
                "The /V value still exists and is fully accessible by parsing code.",
                "!PDFBox does not enforce F = Hidden during rendering \u2014 this widget is VISIBLE on screen.",
                "!pdf.js also renders hidden annotation flags visibly. Only Acrobat/Reader hides this.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0.7 0.1 0.1 rg");
        PDAnnotationWidget w = widgetInZone(z, 320f, 18f);
        linkWidget(f, w);
        setAnnotationFlags(w, 2); // Hidden
        f.setValue("I should be HIDDEN (F=2) but most viewers show me anyway");
        addField(f);
    }

    private static void tc_zeroHeightWidget() throws IOException {
        float[] z = drawBox(
            "EDGE CASE \u2014 Zero-Height Widget Rect  (/Rect height = 0)",
            new String[]{
                "Widget with /Rect [x y x y] (height = 0) is technically legal. No visible output.",
                "!Code dividing by widget height (font auto-sizing, layout) gets NaN or ArithmeticException.",
                "!PDFBox PDRectangle.getHeight() returns 0. Any AP stream content into 0-height BBox is invisible.",
                "+Guard: Math.max(1f, rect.getHeight()) before any division. Your PdfFormFieldBuilder does this.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        float cx = z[0] + 10f;
        float cy = z[1] + z[3] / 2f;
        PDAnnotationWidget w = mkWidget(cx, cy, 200f, 0f); // height = 0
        linkWidget(f, w);
        f.setValue("Zero height widget value (invisible)");
        addField(f);
        // Draw a visible indicator showing where the 0-height widget is
        setStroke(ORANGE);
        cs.setLineWidth(1.5f);
        cs.moveTo(cx - 4, cy);
        cs.lineTo(cx + 204, cy);
        cs.stroke();
        setFill(ORANGE);
        drawText(bodyFont, 8f, cx + 210f, cy - 3f, "<-- 0px high widget here");
    }

    private static void tc_veryLongValue() throws IOException {
        float[] z = drawBox(
            "EDGE CASE \u2014 Very Long /V Value  (1 000+ characters in a text field)",
            new String[]{
                "PDF strings have no spec-defined maximum length. A /V with 1000+ chars is valid.",
                "For multiline fields, PDFBox clips the AP stream to visible lines; /V holds the full text.",
                "!JSON serialisation of the full /V may exceed browser message-size or DOM limits.",
                "!Some validators cap /V at 32 KB or 65 KB and incorrectly reject valid long values.",
            }, 54f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setMultiline(true);
        f.setDefaultAppearance("/" + fontResName + " 8 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 400f, 48f);
        linkWidget(f, w);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) sb.append("Line ").append(i).append(": data value row ").append(i).append(".\n");
        f.setValue(sb.toString());
        addField(f);
    }

    private static void tc_hierarchicalName() throws IOException {
        float[] z = drawBox(
            "EDGE CASE \u2014 Hierarchical Field Name  (form.section.person.name)",
            new String[]{
                "Field names use dots as hierarchy separators: 'form.section.person.name' = 4 nested nodes.",
                "Each dot level is a PDNonTerminalField. The /T (partial name) of each node has no dot.",
                "!A flat field with a literal dot in /T vs a proper nested structure is resolved differently.",
                "!FQNs with dots used as HTML attribute values conflict with CSS class selector syntax.",
            }, 28f);

        // Create the hierarchy: form > section > person > name (terminal)
        PDNonTerminalField form    = new PDNonTerminalField(acroForm);
        PDNonTerminalField section = new PDNonTerminalField(acroForm);
        PDNonTerminalField person  = new PDNonTerminalField(acroForm);
        PDTextField        name    = new PDTextField(acroForm);

        form.setPartialName("form_" + uid());
        section.setPartialName("section");
        person.setPartialName("person");
        name.setPartialName("name");

        name.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 300f, 18f);
        linkWidget(name, w);
        name.setValue("Nested field value");

        // Build hierarchy manually via COS Kids arrays
        COSArray personKids = new COSArray(); personKids.add(name.getCOSObject());
        person.getCOSObject().setItem(COSName.KIDS, personKids);
        COSArray sectionKids = new COSArray(); sectionKids.add(person.getCOSObject());
        section.getCOSObject().setItem(COSName.KIDS, sectionKids);
        COSArray formKids = new COSArray(); formKids.add(section.getCOSObject());
        form.getCOSObject().setItem(COSName.KIDS, formKids);

        acroForm.getFields().add(form);
    }

    private static void tc_missingDA() throws IOException {
        float[] z = drawBox(
            "EDGE CASE \u2014 Missing Default Appearance  (no /DA on field, no global /DA on AcroForm)",
            new String[]{
                "When /DA is absent on both the field and the AcroForm, viewers must synthesise a fallback.",
                "Acrobat falls back to Helvetica 12pt. PDFBox generates no AP stream (blank rendering).",
                "!NeedAppearances=true + missing /DA: field renders completely blank in pdf.js.",
                "!Do not confuse with inherited /DA: a field without /DA inherits from the nearest parent.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        // Deliberately omit /DA
        f.getCOSObject().removeItem(COSName.DA);
        PDAnnotationWidget w = widgetInZone(z, 280f, 18f);
        linkWidget(f, w);
        f.getCOSObject().setString(COSName.V, "Value with no /DA (will appear blank in most viewers)");
        acroForm.getFields().add(f);
    }

    private static void tc_dvVMismatch() throws IOException {
        float[] z = drawBox(
            "EDGE CASE \u2014 /V and /DV Mismatch  (current value \u2260 default value)",
            new String[]{
                "/DV is the 'default value' restored by a ResetForm action. /V is the current value.",
                "Here /V = 'Changed value' but /DV = 'Original default' \u2014 ResetForm will restore to /DV.",
                "!PDFBox high-level API has no getDefaultValue() method; requires raw getCOSObject().getString(DA).",
                "!Some validators incorrectly flag /V != /DV as a form-integrity error.",
            }, 28f);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + fontResName + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 320f, 18f);
        linkWidget(f, w);
        f.setValue("Changed value  (/V)");
        f.getCOSObject().setString(COSName.DV, "Original default  (/DV, used by ResetForm)");
        addField(f);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  FONT EMBEDDING VARIANT TEST CASES
    // ════════════════════════════════════════════════════════════════════════════

    /** Load a font from an InputStream, add it to the AcroForm /DR /Font dict, return its resource name. */
    private static String addFontToDR(PDFont font) {
        PDResources dr = acroForm.getDefaultResources();
        COSName name = dr.add(font);
        return name.getName();
    }

    /**
     * 2×3 grid: six text fields each using a different font embedding strategy.
     * Cell 0: plain / no MK (inherits AcroForm default = FreeSans full-embed).
     * Cell 1: FreeSans — explicitly fully embedded (same font, different resource entry).
     * Cell 2: FreeSans — subset-embedded (PDType0Font embed=true; only glyphs in the value).
     * Cell 3: Lato Regular — different TTF, fully embedded.
     * Cell 4: Standard Type1 Helvetica — NOT embedded; viewer must supply the font.
     * Cell 5: Standard Type1 Courier — NOT embedded; fixed-width viewer fallback.
     */
    private static void tc_fontVariants() throws IOException {
        float[][] zones = drawGrid2x3Box(
            "FONT EMBEDDING \u2014 Text Field Variants  (Plain / Full-embed / Subset / Lato / Helvetica / Courier)",
            new String[]{
                "Plain \u2014 no MK, inherits AcroForm DA",
                "FreeSans \u2014 fully embedded (all glyphs)",
                "FreeSans \u2014 subset (only glyphs in value)",
                "Lato Regular \u2014 different TTF, full embed",
                "Helvetica \u2014 Standard Type1, NOT embedded",
                "Courier \u2014 Standard Type1, NOT embedded",
            }, 20f);

        // Load font variants (cell 0 reuses bodyFont implicitly; cells 1-5 load explicitly)
        PDFont freeSansFull   = PDType0Font.load(doc, Files.newInputStream(fontPath), false);
        PDFont freeSansSubset = PDType0Font.load(doc, Files.newInputStream(fontPath), true);
        Path   latoPath       = fontPath.resolveSibling("Lato-Regular.ttf");
        PDFont latoFull       = PDType0Font.load(doc, Files.newInputStream(latoPath), false);
        PDFont helv           = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont courier        = new PDType1Font(Standard14Fonts.FontName.COURIER);

        String fullResName   = addFontToDR(freeSansFull);
        String subsetResName = addFontToDR(freeSansSubset);
        String latoResName   = addFontToDR(latoFull);
        String helvResName   = addFontToDR(helv);
        String courierResName = addFontToDR(courier);

        // Parallel arrays: da suffix, font resource name (null = use bodyFont / no MK)
        String[] daFontNames = {null, fullResName, subsetResName, latoResName, helvResName, courierResName};
        String[] values = {
            "Plain default (no styling)",
            "FreeSans fully embedded",
            "Subset: only these chars",
            "Lato Regular embedded",
            "Helvetica (viewer-provided)",
            "Courier (viewer-provided)",
        };

        // For subset font: call encode on all chars in the value so they are included in the subset
        freeSansSubset.encode(values[2]);

        for (int i = 0; i < 6; i++) {
            PDTextField f = new PDTextField(acroForm);
            f.setPartialName(uid());
            String fontName = (i == 0) ? fontResName : daFontNames[i];
            f.setDefaultAppearance("/" + fontName + " 10 Tf 0 g");
            float ww = zones[i][2] - 16f;
            float wx = zones[i][0] + 8f;
            float wy = zones[i][1] + (zones[i][3] - 16f) / 2f;
            PDAnnotationWidget w = mkWidget(wx, wy, ww, 16f);
            linkWidget(f, w);
            if (i > 0) setMkColors(w, CELL_BG_THEMES[i], CELL_BC_THEMES[i]);
            f.setValue(values[i]);
            addField(f);
        }
    }

    /**
     * 2×3 grid: six checkboxes / radio buttons using different font strategies in their /DA.
     * Cell 0: plain / no MK — inherits AcroForm default DA (FreeSans full).
     * Cell 1: explicit /DA removed — viewer must fall back to built-in heuristic.
     * Cell 2: ZapfDingbats (/ZaDb) — traditional PDF checkbox glyph font.
     * Cell 3: embedded TrueType (Lato) — custom font in per-field /DA.
     * Cell 4: embedded subset (FreeSans, only 2 glyphs: on-state 'l' + off-state ' ').
     * Cell 5: Standard Type1 Helvetica — not embedded; uses viewer-supplied Helv.
     */
    private static void tc_checkboxFontVariants() throws IOException {
        float[][] zones = drawGrid2x3Box(
            "CHECKBOXES \u2014 Font Embedding Variants  (Plain / No-DA / ZapfDingbats / Lato / 2-glyph subset / Helvetica)",
            new String[]{
                "Plain \u2014 inherits AcroForm DA",
                "No /DA \u2014 viewer fallback",
                "ZapfDingbats (/ZaDb) \u2014 traditional",
                "Lato Regular \u2014 embedded TTF",
                "2-glyph subset (only 'l' + ' ')",
                "Helvetica \u2014 Standard Type1",
            }, 18f);

        // Prepare fonts for cells 2-5
        PDFont zadb    = new PDType1Font(Standard14Fonts.FontName.ZAPF_DINGBATS);
        Path   latoPath = fontPath.resolveSibling("Lato-Regular.ttf");
        PDFont lato    = PDType0Font.load(doc, Files.newInputStream(latoPath), false);
        PDFont subset2 = PDType0Font.load(doc, Files.newInputStream(fontPath), true);
        PDFont helv    = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

        // Register only 2 glyphs for subset2 (on-state char 'l' + space for off-state)
        subset2.encode("l ");

        String zadbRes   = addFontToDR(zadb);
        String latoRes   = addFontToDR(lato);
        String sub2Res   = addFontToDR(subset2);
        String helvRes   = addFontToDR(helv);

        // Per-cell /DA strings (null means use bodyFont)
        String[] daStrings = {
            null,                                              // 0: inherits
            null,                                              // 1: will be removed
            "/" + zadbRes   + " 12 Tf 0 g",                   // 2: ZapfDingbats
            "/" + latoRes   + " 10 Tf 0 g",                   // 3: Lato
            "/" + sub2Res   + " 10 Tf 0 g",                   // 4: 2-glyph subset
            "/" + helvRes   + " 10 Tf 0 g",                   // 5: Helvetica
        };

        boolean[] checked = {true, true, true, true, true, true};

        for (int i = 0; i < 6; i++) {
            PDCheckBox f = new PDCheckBox(acroForm);
            f.setPartialName(uid());
            if (i == 1) {
                // Explicitly remove /DA to force viewer fallback
                f.getCOSObject().removeItem(COSName.DA);
            } else if (daStrings[i] != null) {
                f.getCOSObject().setString(COSName.DA, daStrings[i]);
            }
            float cx = zones[i][0] + 8f;
            float cy = zones[i][1] + (zones[i][3] - 14f) / 2f;
            PDAnnotationWidget w = mkWidget(cx, cy, 14f, 14f);
            linkWidget(f, w);
            if (i > 0) setMkColors(w, CELL_BG_THEMES[i], CELL_BC_THEMES[i]);
            if (i > 0) setBorderStyle(w, "S", 1.5f);
            addField(f);
            if (checked[i]) {
                try { f.check(); } catch (Exception ignored) {}
            }
            setFill(i > 0 ? CELL_BC_THEMES[i] : DARK_TXT);
            drawText(bodyFont, 8f, cx + 18f, cy + 2f, "Checked");
        }
    }

    /**
     * Single detailed box: a text field whose embedded font subset contains ONLY the exact
     * characters present in the field's value — nothing more. This minimises the embedded
     * font byte overhead for forms with fixed, known values.
     */
    private static void tc_textFieldValueSubset() throws IOException {
        String value = "AcroForm subset demo";  // 14 unique code-points
        float[] z = drawBox(
            "TEXT \u2014 Value-Only Font Subset  (embedded glyphs = exactly the chars in /V)",
            new String[]{
                "Font loaded with PDType0Font.load(doc, stream, embedSubset=true).",
                "Before setValue(), font.encode(value) is called to register only the needed glyph IDs.",
                "At doc.save() time, PDFBox subsets the CIDFont to those GIDs only \u2014 minimising file size.",
                "!If /V is later changed (e.g., by a viewer) to include a new char, that glyph is MISSING.",
                "!The subset font cannot render characters beyond those registered at generation time.",
                "+Value: \"" + value + "\" \u2014 unique glyphs: " + uniqueCount(value) + " code-points embedded.",
            }, 28f);

        PDFont subsetFont = PDType0Font.load(doc, Files.newInputStream(fontPath), true);
        // Register all chars in the value — this determines exactly which glyphs get embedded
        subsetFont.encode(value);
        String subsetRes = addFontToDR(subsetFont);

        PDTextField f = new PDTextField(acroForm);
        f.setPartialName(uid());
        f.setDefaultAppearance("/" + subsetRes + " 11 Tf 0 g");
        PDAnnotationWidget w = widgetInZone(z, 340f, 18f);
        setMkColors(w, CELL_BG_BLUE, CELL_BC_BLUE);
        linkWidget(f, w);
        f.setValue(value);
        addField(f);
    }

    /** Count distinct Unicode code-points in a string (used in description text). */
    private static int uniqueCount(String s) {
        return (int) s.codePoints().distinct().count();
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  LOW-LEVEL DRAWING HELPERS
    // ════════════════════════════════════════════════════════════════════════════

    private static void setFill(float r, float g, float b) throws IOException {
        cs.setNonStrokingColor(r, g, b);
    }

    private static void setFill(float[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
    }

    private static void setStroke(float[] rgb) throws IOException {
        cs.setStrokingColor(rgb[0], rgb[1], rgb[2]);
    }

    private static void fillRect(float x, float y, float w, float h) throws IOException {
        cs.addRect(x, y, w, h);
        cs.fill();
    }

    private static void strokeRect(float x, float y, float w, float h) throws IOException {
        cs.addRect(x, y, w, h);
        cs.stroke();
    }

    private static void drawText(PDFont font, float size, float x, float y, String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(filterEncodable(text));
        cs.endText();
    }

    /** Remove characters that FreeSans cannot encode to avoid IOException during showText(). */
    private static String filterEncodable(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F) {
                sb.append(' ');
            } else {
                try {
                    bodyFont.encode(new String(Character.toChars(cp)));
                    sb.appendCodePoint(cp);
                } catch (Exception e) {
                    sb.append('?');
                }
            }
            i += len;
        }
        return sb.toString();
    }
}
