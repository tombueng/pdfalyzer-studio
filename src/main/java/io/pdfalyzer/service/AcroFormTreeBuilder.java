package io.pdfalyzer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDPushButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;
import org.springframework.stereotype.Component;

import io.pdfalyzer.model.PdfNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the AcroForm subtree for a PDF document.
 * Used by {@link SemanticTreeBuilder}.
 */
@Component
@Slf4j
public class AcroFormTreeBuilder {

    /**
     * ZapfDingbats character code (single byte, as it appears in MK/CA) → Unicode string.
     * Only the subset commonly used as checkbox/radio symbols.
     */
    private static final Map<Character, String> ZAPF_TO_UNICODE = Map.ofEntries(
        Map.entry('4',  "\u2714"),  // ✔ HEAVY CHECK MARK
        Map.entry('8',  "\u2717"),  // ✗ BALLOT X
        Map.entry('l',  "\u2713"),  // ✓ CHECK MARK
        Map.entry('n',  "\u2715"),  // ✕ MULTIPLICATION X
        Map.entry('m',  "\u25fc"),  // ◼ BLACK MEDIUM SQUARE
        Map.entry('H',  "\u2022"),  // • BULLET
        Map.entry('R',  "\u25cf"),  // ● BLACK CIRCLE
        Map.entry('q',  "\u25a0"),  // ■ BLACK SQUARE
        Map.entry('o',  "\u25a1"),  // □ WHITE SQUARE
        Map.entry('r',  "\u25cb"),  // ○ WHITE CIRCLE
        Map.entry('P',  "\u2605"),  // ★ BLACK STAR
        Map.entry('a',  "\u25c6")   // ◆ BLACK DIAMOND
    );

    /** Standard PDF-14 font BaseFont names → CSS font-family fallback. */
    private static final Map<String, String> STD14_CSS = Map.ofEntries(
        Map.entry("Helv",            "Helvetica, Arial, sans-serif"),
        Map.entry("Helvetica",       "Helvetica, Arial, sans-serif"),
        Map.entry("Arial",           "Arial, Helvetica, sans-serif"),
        Map.entry("TiRo",            "Times New Roman, Times, serif"),
        Map.entry("TimesNewRoman",   "Times New Roman, Times, serif"),
        Map.entry("Times-Roman",     "Times New Roman, Times, serif"),
        Map.entry("Times",           "Times New Roman, Times, serif"),
        Map.entry("Cour",            "Courier New, Courier, monospace"),
        Map.entry("Courier",         "Courier New, Courier, monospace"),
        Map.entry("CourierNew",      "Courier New, Courier, monospace"),
        Map.entry("Courier-New",     "Courier New, Courier, monospace"),
        Map.entry("Symbol",          "Symbol, serif"),
        Map.entry("ZapfDingbats",    "'Zapf Dingbats', 'Dingbats', serif")
    );

    private final CosNodeBuilder cosBuilder;

    public AcroFormTreeBuilder(CosNodeBuilder cosBuilder) {
        this.cosBuilder = cosBuilder;
    }

    PdfNode buildAcroFormTree(PDAcroForm acroForm, CosNodeBuilder.ParseContext ctx) {
        List<PDField> fields = acroForm.getFields();
        PdfNode formNode = new PdfNode("acroform",
                "AcroForm (" + fields.size() + " fields)", "acroform", "fa-wpforms", "#0dcaf0");
        formNode.setNodeCategory("acroform");
        if (acroForm.isSignaturesExist())
            formNode.addProperty("SignaturesExist", "true");
        formNode.addProperty("NeedAppearances", String.valueOf(acroForm.getNeedAppearances()));
        for (PDField field : fields) {
            formNode.addChild(buildFieldNode(field, ctx));
        }
        try {
            cosBuilder.attachCosChildren(formNode, acroForm.getCOSObject(), "acroform-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to acroform", e);
        }
        return formNode;
    }

    PdfNode buildFieldNode(PDField field, CosNodeBuilder.ParseContext ctx) {
        String fieldType = field.getFieldType();
        String label = (field.getPartialName() != null
                ? field.getPartialName()
                : "(unnamed)") + " [" + fieldType + "]";
        PdfNode node = new PdfNode("field-" + field.getFullyQualifiedName(),
                label, "field", getFieldIcon(fieldType), "#0dcaf0");
        node.setNodeCategory("field");
        node.addProperty("FieldType", fieldType);
        if (field.getFullyQualifiedName() != null)
            node.addProperty("FullName", field.getFullyQualifiedName());
        // For choice fields, getValueAsString() may return bracketed array representation.
        // Read the V entry directly from the COS dictionary to get clean value(s).
        if (field instanceof PDComboBox || field instanceof PDListBox) {
            COSBase vBase = field.getCOSObject().getDictionaryObject(COSName.V);
            if (vBase instanceof COSString cosStr) {
                if (!cosStr.getString().isBlank()) node.addProperty("Value", cosStr.getString());
            } else if (vBase instanceof COSArray vArr && vArr.size() > 0) {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < vArr.size(); i++) {
                    COSBase item = vArr.getObject(i);
                    if (item instanceof COSString s && !s.getString().isBlank()) parts.add(s.getString());
                }
                if (!parts.isEmpty()) node.addProperty("Value", String.join(",", parts));
            }
        } else if (field.getValueAsString() != null && !field.getValueAsString().isEmpty()) {
            node.addProperty("Value", field.getValueAsString());
        }
        node.addProperty("ReadOnly", String.valueOf(field.isReadOnly()));
        node.addProperty("Required", String.valueOf(field.isRequired()));
        node.addProperty("FieldSubType", detectFieldSubType(field));

        if (field instanceof PDTextField textField) {
            node.addProperty("Multiline", String.valueOf(textField.isMultiline()));
        }
        if (field instanceof PDComboBox comboBox) {
            node.addProperty("Editable", String.valueOf(comboBox.isEdit()));
        }
        if (field instanceof PDListBox) {
            int ff = field.getCOSObject().getInt(COSName.FF, 0);
            node.addProperty("MultiSelect", String.valueOf((ff & (1 << 21)) != 0));
        }
        if (field instanceof PDCheckBox) {
            String current = field.getValueAsString();
            boolean checked = current != null && !current.isBlank() && !"Off".equalsIgnoreCase(current);
            node.addProperty("Checked", String.valueOf(checked));
        }
        if (field instanceof PDComboBox || field instanceof PDListBox) {
            String options = extractChoiceOptions(field);
            if (options != null && !options.isBlank()) {
                node.addProperty("Options", options);
            }
        }
        if (field instanceof PDRadioButton rb) {
            List<String> exports = rb.getExportValues();
            if (exports != null && !exports.isEmpty()) {
                node.addProperty("Options", String.join(", ", exports));
            }
            String val = field.getValueAsString();
            if (val != null && !val.isBlank() && !"Off".equalsIgnoreCase(val)) {
                node.addProperty("SelectedOption", val);
            }
        }
        String jsValidation = extractValidationJavaScript(field);
        if (jsValidation != null && !jsValidation.isBlank()) {
            node.addProperty("JavaScript", jsValidation);
        }
        extractAppearanceProperties(field, node, ctx);
        if (!field.getWidgets().isEmpty()) {
            try {
                if (field.getWidgets().get(0).getPage() != null) {
                    int pageIdx = ctx.doc.getPages().indexOf(field.getWidgets().get(0).getPage());
                    if (pageIdx >= 0) {
                        node.setPageIndex(pageIdx);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not resolve page index for field {}", field.getFullyQualifiedName(), e);
            }
            PDRectangle rect = field.getWidgets().get(0).getRectangle();
            if (rect != null) {
                node.setBoundingBox(new double[] {
                        rect.getLowerLeftX(), rect.getLowerLeftY(),
                        rect.getWidth(), rect.getHeight() });
            }
        }
        if (field instanceof PDNonTerminalField) {
            for (PDField child : ((PDNonTerminalField) field).getChildren()) {
                node.addChild(buildFieldNode(child, ctx));
            }
        }
        try {
            cosBuilder.attachCosChildren(node, field.getCOSObject(),
                    "field-" + field.getFullyQualifiedName() + "-cos", ctx, 0);
        } catch (Exception e) {
            log.debug("Error attaching COS to field {}", field.getFullyQualifiedName(), e);
        }
        return node;
    }

    private String detectFieldSubType(PDField field) {
        if (field instanceof PDPushButton) return "button";   // must precede PDCheckBox (both are PDButton)
        if (field instanceof PDCheckBox) return "checkbox";
        if (field instanceof PDRadioButton) return "radio";
        if (field instanceof PDTextField tf) {
            int ff = tf.getCOSObject().getInt(COSName.FF, 0);
            return (ff & 0x2000) != 0 ? "password" : "text";  // bit 14 (1-indexed) = password
        }
        if (field instanceof PDComboBox) return "combo";
        if (field instanceof PDListBox) return "list";
        if (field instanceof PDSignatureField) return "signature";
        return "unknown";
    }

    private String extractChoiceOptions(PDField field) {
        COSBase optBase = field.getCOSObject().getDictionaryObject(COSName.OPT);
        if (!(optBase instanceof COSArray optArray)) return null;
        List<String> values = new ArrayList<>();
        for (int i = 0; i < optArray.size(); i++) {
            COSBase item = optArray.getObject(i);
            if (item instanceof COSString cosString) {
                values.add(cosString.getString());
                continue;
            }
            if (item instanceof COSArray pair && pair.size() > 0) {
                COSBase first = pair.getObject(0);
                COSBase second = pair.size() > 1 ? pair.getObject(1) : null;
                if (second instanceof COSString display)
                    values.add(display.getString());
                else if (first instanceof COSString export)
                    values.add(export.getString());
            }
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private String extractValidationJavaScript(PDField field) {
        COSBase aaBase = field.getCOSObject().getDictionaryObject(COSName.AA);
        if (!(aaBase instanceof COSDictionary aaDict)) return null;
        COSBase validateBase = aaDict.getDictionaryObject(COSName.V);
        if (!(validateBase instanceof COSDictionary validateDict)) return null;
        String subtype = validateDict.getNameAsString(COSName.S);
        if (!"JavaScript".equals(subtype)) return null;
        return validateDict.getString(COSName.JS);
    }


    /** Extracts all visual/appearance attributes from a field and adds them as node properties. */
    private void extractAppearanceProperties(PDField field, PdfNode node, CosNodeBuilder.ParseContext ctx) {
        // Default Appearance (DA) string - present on variable text fields
        String da = null;
        if (field instanceof PDVariableText vt) {
            da = vt.getDefaultAppearance();
        }
        if (da == null || da.isBlank()) {
            da = field.getCOSObject().getString(COSName.DA);
        }
        String fontName = null;
        if (da != null && !da.isBlank()) {
            fontName = parseDaString(da, node);
        }
        // Resolve font name to an embedded font object reference or CSS fallback
        if (fontName != null) {
            String css = STD14_CSS.get(fontName);
            if (css != null) {
                node.addProperty("FontCssFamily", css);
            }
            String objRef = resolveFontObjectRef(fontName, field, ctx);
            if (objRef != null) {
                node.addProperty("FontObjectRef", objRef);
            }
        }

        // Check/radio symbol and export value
        if (field instanceof PDCheckBox || field instanceof PDRadioButton) {
            extractCheckSymbol(field, node, fontName);
        }

        // MaxLen, Comb, and Password for text fields
        if (field instanceof PDTextField tf) {
            int maxLen = tf.getMaxLen();
            if (maxLen > 0) node.addProperty("MaxLength", String.valueOf(maxLen));
            if (tf.isComb() && maxLen > 0) node.addProperty("Comb", "true");
            // Q (quadding / text alignment) - 0=left, 1=center, 2=right
            int q = field.getCOSObject().getInt(COSName.Q, -1);
            if (q >= 0) {
                String[] alignNames = {"left", "center", "right"};
                if (q < alignNames.length) node.addProperty("Alignment", alignNames[q]);
            }
        }
        if (field instanceof PDComboBox || field instanceof PDListBox) {
            int q = field.getCOSObject().getInt(COSName.Q, -1);
            if (q >= 0) {
                String[] alignNames = {"left", "center", "right"};
                if (q < alignNames.length) node.addProperty("Alignment", alignNames[q]);
            }
        }

        // Widget-level appearance: MK dictionary (rotation, border/bg colors) and BS dictionary
        if (!field.getWidgets().isEmpty()) {
            COSDictionary widgetDict = field.getWidgets().get(0).getCOSObject();

            COSBase mkBase = widgetDict.getDictionaryObject(COSName.MK);
            if (mkBase instanceof COSDictionary mk) {
                // Rotation lives in /MK /R on the widget, not on the field dict itself
                int rotate = mk.getInt(COSName.getPDFName("R"), -1);
                if (rotate > 0) node.addProperty("Rotation", String.valueOf(rotate));
                String bc = cosArrayToHex(mk.getDictionaryObject(COSName.BC));
                if (bc != null) node.addProperty("BorderColor", bc);
                String bg = cosArrayToHex(mk.getDictionaryObject(COSName.BG));
                if (bg != null) node.addProperty("BackgroundColor", bg);
                // Push-button label: /MK /CA (Normal Caption)
                if (field instanceof PDPushButton) {
                    extractButtonCaption(mk, field.getCOSObject(), node);
                }
            }

            COSBase bsBase = widgetDict.getDictionaryObject(COSName.BS);
            if (bsBase instanceof COSDictionary bs) {
                COSBase wBase = bs.getDictionaryObject(COSName.W);
                if (wBase instanceof COSNumber wNum) {
                    node.addProperty("BorderWidth", String.valueOf(wNum.floatValue()));
                }
                String style = bs.getNameAsString(COSName.S);
                if (style != null && !style.isBlank()) {
                    node.addProperty("BorderStyle", mapBsStyleName(style));
                }
            }
        }
    }

    /**
     * Extracts the push-button normal caption (/MK /CA) from the widget MK dict,
     * with a fallback to the field dict's own MK entry (some PDFs merge field + widget).
     */
    private void extractButtonCaption(COSDictionary widgetMk, COSDictionary fieldDict, PdfNode node) {
        COSBase ca = widgetMk.getDictionaryObject(COSName.getPDFName("CA"));
        // Fallback: some writers place MK on the field dict when field==widget
        if (!(ca instanceof COSString)) {
            COSBase fieldMkBase = fieldDict.getDictionaryObject(COSName.MK);
            if (fieldMkBase instanceof COSDictionary fieldMk) {
                ca = fieldMk.getDictionaryObject(COSName.getPDFName("CA"));
            }
        }
        if (ca instanceof COSString caStr) {
            String caption = caStr.getString();
            if (caption != null && !caption.isBlank()) {
                node.addProperty("ButtonCaption", caption);
            }
        }
    }

    /**
     * Parses a DA string like "/Helv 10 Tf 0 g" or "/TiRo 12 Tf 0.5 0 0 rg".
     * Adds FontName, FontSize, TextColor properties to the node.
     * Returns the parsed font name, or null if none found.
     */
    private String parseDaString(String da, PdfNode node) {
        // Font name and size: /Name size Tf
        Pattern tfPattern = Pattern.compile("/([\\w+]+)\\s+(\\d+(?:\\.\\d+)?)\\s+Tf");
        Matcher tfMatcher = tfPattern.matcher(da);
        String fontName = null;
        if (tfMatcher.find()) {
            fontName = tfMatcher.group(1);
            node.addProperty("FontName", fontName);
            node.addProperty("FontSize", tfMatcher.group(2));
        }
        // Greyscale color: <n> g  (n in 0-1)
        Pattern gPattern = Pattern.compile("([\\d.]+)\\s+g(?:\\s|$)");
        Matcher gMatcher = gPattern.matcher(da);
        if (gMatcher.find()) {
            float grey = Float.parseFloat(gMatcher.group(1));
            int v = Math.round(grey * 255);
            node.addProperty("TextColor", String.format("#%02x%02x%02x", v, v, v));
            return fontName;
        }
        // RGB color: <r> <g> <b> rg
        Pattern rgbPattern = Pattern.compile("([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+rg(?:\\s|$)");
        Matcher rgbMatcher = rgbPattern.matcher(da);
        if (rgbMatcher.find()) {
            int r = Math.round(Float.parseFloat(rgbMatcher.group(1)) * 255);
            int g = Math.round(Float.parseFloat(rgbMatcher.group(2)) * 255);
            int b = Math.round(Float.parseFloat(rgbMatcher.group(3)) * 255);
            node.addProperty("TextColor", String.format("#%02x%02x%02x", r, g, b));
        }
        return fontName;
    }

    /**
     * Resolves a DA font name to a PDF object reference "objNum genNum" for embedded fonts.
     * Checks the widget's own /Resources first, then the AcroForm /DR.
     */
    private String resolveFontObjectRef(String fontName, PDField field, CosNodeBuilder.ParseContext ctx) {
        COSName cn = COSName.getPDFName(fontName);

        // 1. Widget's own /Resources /Font
        if (!field.getWidgets().isEmpty()) {
            COSDictionary wRes = field.getWidgets().get(0).getCOSObject()
                    .getCOSDictionary(COSName.RESOURCES);
            if (wRes != null) {
                COSDictionary fontDict = wRes.getCOSDictionary(COSName.FONT);
                if (fontDict != null) {
                    COSBase item = fontDict.getItem(cn);
                    if (item instanceof COSObject cosObj) {
                        return cosObj.getObjectNumber() + " " + cosObj.getGenerationNumber();
                    }
                }
            }
        }

        // 2. AcroForm /DR /Font
        try {
            var acroForm = ctx.doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null) {
                COSDictionary dr = acroForm.getCOSObject().getCOSDictionary(COSName.DR);
                if (dr != null) {
                    COSDictionary fontDict = dr.getCOSDictionary(COSName.FONT);
                    if (fontDict != null) {
                        COSBase item = fontDict.getItem(cn);
                        if (item instanceof COSObject cosObj) {
                            return cosObj.getObjectNumber() + " " + cosObj.getGenerationNumber();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve font object ref for {}", fontName, e);
        }
        return null;
    }

    /**
     * Extracts the check/on symbol for checkbox and radio fields.
     * Reads MK/CA from the widget, maps ZapfDingbats codes to Unicode where possible.
     * Adds CheckSymbol and OnValue properties to the node.
     */
    private void extractCheckSymbol(PDField field, PdfNode node, String fontName) {
        // OnValue: the export value for the "On" state
        String onValue = null;
        if (field instanceof PDCheckBox cb) {
            try { onValue = cb.getOnValue(); } catch (Exception e) { /* ignore */ }
        }
        if (onValue == null || onValue.isBlank()) onValue = "Yes";
        node.addProperty("OnValue", onValue);

        // MK/CA: the caption character used in the widget appearance
        if (field.getWidgets().isEmpty()) return;
        PDAnnotationWidget widget = field.getWidgets().get(0);
        PDAppearanceCharacteristicsDictionary mk = widget.getAppearanceCharacteristics();
        if (mk == null) return;
        String caption = mk.getNormalCaption();
        if (caption == null || caption.isEmpty()) return;

        // If font is ZapfDingbats (or unknown — default for most PDF checkboxes), map to Unicode
        boolean isZapf = fontName == null
                || "ZapfDingbats".equalsIgnoreCase(fontName)
                || fontName.toLowerCase().contains("zapf");
        if (isZapf && caption.length() == 1) {
            String mapped = ZAPF_TO_UNICODE.get(caption.charAt(0));
            if (mapped != null) {
                node.addProperty("CheckSymbol", mapped);
                return;
            }
        }
        // For non-ZapfDingbats or unmapped chars: store the raw caption
        // (browser renders it using the loaded @font-face if FontObjectRef is available)
        node.addProperty("CheckSymbol", caption);
    }

    /** Converts a COSArray of 1 (grey), 3 (RGB), or 4 (CMYK) numbers to "#RRGGBB". Returns null if not applicable. */
    private String cosArrayToHex(COSBase base) {
        if (!(base instanceof COSArray arr)) return null;
        if (arr.size() == 1 && arr.get(0) instanceof COSNumber n) {
            int v = Math.round(n.floatValue() * 255);
            return String.format("#%02x%02x%02x", v, v, v);
        }
        if (arr.size() == 3
                && arr.get(0) instanceof COSNumber r
                && arr.get(1) instanceof COSNumber g
                && arr.get(2) instanceof COSNumber b) {
            return String.format("#%02x%02x%02x",
                    Math.round(r.floatValue() * 255),
                    Math.round(g.floatValue() * 255),
                    Math.round(b.floatValue() * 255));
        }
        return null;
    }

    /** Maps PDF border style name (S, D, B, I, U) to human-readable name used in the schema. */
    private String mapBsStyleName(String s) {
        switch (s) {
            case "S": return "solid";
            case "D": return "dashed";
            case "B": return "beveled";
            case "I": return "inset";
            case "U": return "underline";
            default: return s.toLowerCase();
        }
    }

    private String getFieldIcon(String fieldType) {
        if (fieldType == null) return "fa-question-circle";
        switch (fieldType) {
            case "Tx": return "fa-keyboard";
            case "Btn": return "fa-check-square";
            case "Ch": return "fa-list";
            case "Sig": return "fa-signature";
            default: return "fa-square";
        }
    }
}
