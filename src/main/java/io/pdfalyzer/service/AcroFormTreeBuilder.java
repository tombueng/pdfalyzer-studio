package io.pdfalyzer.service;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
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
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.util.Store;
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

    PdfNode buildAcroFormTree(PDAcroForm acroForm, CosNodeBuilder.ParseContext ctx,
                              Map<String, String> serialToBadge) {
        List<PDField> fields = acroForm.getFields();
        PdfNode formNode = new PdfNode("acroform",
                "AcroForm (" + fields.size() + " fields)", "acroform", "fa-wpforms", "#0dcaf0");
        formNode.setNodeCategory("acroform");

        // Decode AcroForm-level properties
        formNode.addProperty("NeedAppearances", String.valueOf(acroForm.getNeedAppearances()));

        // SigFlags (ISO 32000-2 §12.7.3.2)
        COSDictionary acroDict = acroForm.getCOSObject();
        int sigFlags = acroDict.getInt(COSName.getPDFName("SigFlags"), 0);
        if (sigFlags != 0) {
            formNode.addProperty("SigFlags", formatBitfield(sigFlags, SIGFLAGS_BITS));
        }

        // DA — default appearance string (font/size/color fallback for all fields)
        String da = acroDict.getString(COSName.DA);
        if (da != null && !da.isBlank()) {
            formNode.addProperty("Default Appearance (DA)", da);
        }

        // Q — default quadding (text alignment) for all fields
        int q = acroDict.getInt(COSName.Q, -1);
        if (q >= 0) {
            String[] alignNames = { "left", "center", "right" };
            formNode.addProperty("Default Alignment (Q)", q < alignNames.length ? alignNames[q] : String.valueOf(q));
        }

        // DR — default resources (decoded as semantic child with font listing)
        COSBase drBase = acroDict.getDictionaryObject(COSName.DR);
        if (drBase != null) {
            COSBase drResolved = drBase;
            if (drResolved instanceof COSObject) drResolved = ((COSObject) drResolved).getObject();
            if (drResolved instanceof COSDictionary drDict) {
                PdfNode drNode = new PdfNode("acroform-dr", "Default Resources (DR)",
                        "folder", "fa-cubes", "#6f42c1");
                drNode.setNodeCategory("acroform-resources");
                // List fonts in DR
                COSBase fontBase = drDict.getDictionaryObject(COSName.FONT);
                if (fontBase instanceof COSDictionary fontDict) {
                    PdfNode fontsNode = new PdfNode("acroform-dr-fonts",
                            "Fonts (" + fontDict.size() + ")", "folder", "fa-font", "#6f42c1");
                    fontsNode.setNodeCategory("acroform-fonts");
                    for (Map.Entry<COSName, COSBase> entry : fontDict.entrySet()) {
                        String fontAlias = entry.getKey().getName();
                        PdfNode fontNode = new PdfNode("acroform-font-" + fontAlias,
                                "/" + fontAlias, "font-ref", "fa-font", "#6f42c1");
                        fontNode.setNodeCategory("acroform-font");
                        String css = STD14_CSS.get(fontAlias);
                        if (css != null) fontNode.addProperty("CSS Family", css);
                        COSBase fontVal = entry.getValue();
                        if (fontVal instanceof COSObject fontObj) {
                            fontNode.addProperty("Object", fontObj.getObjectNumber() + " " + fontObj.getGenerationNumber() + " R");
                            fontNode.setObjectNumber((int) fontObj.getObjectNumber());
                            fontNode.setGenerationNumber(fontObj.getGenerationNumber());
                        }
                        fontsNode.addChild(fontNode);
                    }
                    drNode.addChild(fontsNode);
                }
                cosBuilder.attachCosChildren(drNode, drDict, "acroform-dr-cos", ctx, 0,
                        Set.of("Font", "Type"));
                formNode.addChild(drNode);
            }
        }

        // XFA — warn if present (XFA forms are deprecated and not widely supported)
        if (acroDict.getDictionaryObject(COSName.getPDFName("XFA")) != null) {
            formNode.addProperty("XFA", "Present (deprecated XML Forms Architecture — limited viewer support)");
        }

        for (PDField field : fields) {
            formNode.addChild(buildFieldNode(field, ctx, serialToBadge));
        }

        // Exclude keys already decoded above or represented as semantic children
        Set<String> excludeKeys = Set.of(
                "Fields", "NeedAppearances", "SigFlags", "DA", "DR", "Q", "XFA", "Type");
        try {
            cosBuilder.attachCosChildren(formNode, acroDict, "acroform-cos", ctx, 0, excludeKeys);
        } catch (Exception e) {
            log.debug("Error attaching COS to acroform", e);
        }
        return formNode;
    }

    PdfNode buildFieldNode(PDField field, CosNodeBuilder.ParseContext ctx,
                           Map<String, String> serialToBadge) {
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

        // Field flags /Ff (ISO 32000-2 §12.7.4.1)
        int ff = field.getCOSObject().getInt(COSName.FF, 0);
        if (ff != 0) {
            node.addProperty("FieldFlags (Ff)", formatBitfield(ff, getFieldFlagBits(field)));
        }

        if (field instanceof PDTextField textField) {
            node.addProperty("Multiline", String.valueOf(textField.isMultiline()));
        }
        if (field instanceof PDComboBox comboBox) {
            node.addProperty("Editable", String.valueOf(comboBox.isEdit()));
        }
        if (field instanceof PDListBox) {
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
        // For signature fields: extract signer cert serial and apply DSS badge
        if (field instanceof PDSignatureField sigField) {
            applySignatureBadge(sigField, node, ctx, serialToBadge);
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
                node.addChild(buildFieldNode(child, ctx, serialToBadge));
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
            // Annotation flags /F (ISO 32000-2 §12.5.3)
            int annotFlags = widgetDict.getInt(COSName.F, 0);
            if (annotFlags != 0) node.addProperty("AnnotationFlags", formatBitfield(annotFlags, ANNOT_FLAGS_BITS));

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

    /** Badge colors — must match DocumentStructureTreeBuilder.BADGE_COLORS. */
    private static final String[] BADGE_COLORS = {
        "#0d6efd", "#198754", "#dc3545", "#fd7e14", "#6f42c1", "#20c997",
        "#d63384", "#0dcaf0", "#ffc107", "#6610f2", "#e83e8c", "#17a2b8"
    };

    /**
     * Extracts the signer certificate serial from the CMS/PKCS#7 data embedded in
     * a signature field and applies the matching DSS badge (number + color).
     */
    @SuppressWarnings("unchecked")
    private void applySignatureBadge(PDSignatureField sigField, PdfNode node,
                                     CosNodeBuilder.ParseContext ctx,
                                     Map<String, String> serialToBadge) {
        try {
            PDSignature sig = sigField.getSignature();
            if (sig == null) {
                log.debug("Signature field {} has no signature object", sigField.getFullyQualifiedName());
                return;
            }

            // Get CMS contents from the signature dictionary
            byte[] contents = sig.getContents();
            if (contents == null || contents.length == 0) {
                log.debug("Signature field {} has empty contents", sigField.getFullyQualifiedName());
                return;
            }
            log.debug("Signature field {} has {} bytes of CMS data", sigField.getFullyQualifiedName(), contents.length);

            CMSSignedData cmsData = new CMSSignedData(contents);
            Collection<SignerInformation> signers = cmsData.getSignerInfos().getSigners();
            if (signers.isEmpty()) {
                log.debug("No signers found in CMS data for field {}", sigField.getFullyQualifiedName());
                return;
            }

            SignerInformation signer = signers.iterator().next();
            Store<X509CertificateHolder> certStore = cmsData.getCertificates();
            Collection<X509CertificateHolder> certs = certStore.getMatches(signer.getSID());
            if (certs.isEmpty()) {
                log.debug("No matching certs found in CMS data for field {}", sigField.getFullyQualifiedName());
                return;
            }

            X509CertificateHolder certHolder = certs.iterator().next();
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certHolder);
            String serial = cert.getSerialNumber().toString(16).toUpperCase();

            if (serialToBadge == null) {
                log.debug("Signature field {} signer cert serial={}, serialToBadge=null",
                        sigField.getFullyQualifiedName(), serial);
                // No DSS in document at all
                node.setBadge("No DSS");
                node.setBadgeColor("#6c757d");
                log.debug("No DSS in document — applied 'No DSS' badge to signature field {}",
                        sigField.getFullyQualifiedName());
            } else {
                // Look up the badge assigned during DSS tree building
                String badge = serialToBadge.get(serial);
                if (badge != null) {
                    node.setBadge(badge);
                    try {
                        int idx = (Integer.parseInt(badge) - 1) % BADGE_COLORS.length;
                        node.setBadgeColor(BADGE_COLORS[idx]);
                    } catch (NumberFormatException e) {
                        node.setBadgeColor(BADGE_COLORS[0]);
                    }
                    log.debug("Applied badge {} to signature field {}", badge, sigField.getFullyQualifiedName());
                } else {
                    log.debug("No badge match for serial {} in serialToBadge map", serial);
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract signer cert from signature field {}: {}",
                    sigField.getFullyQualifiedName(), e.getMessage(), e);
        }
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

    // ── Bitfield decoding ────────────────────────────────────────────────

    /** SigFlags bits (ISO 32000-2 §12.7.3.2) */
    private static final String[][] SIGFLAGS_BITS = {
        { "1", "SignaturesExist", "Document contains at least one signature field" },
        { "2", "AppendOnly", "Document must be saved incrementally (append-only) to preserve signatures" },
    };

    /** Annotation flags /F bits (ISO 32000-2 §12.5.3, Table 64) */
    private static final String[][] ANNOT_FLAGS_BITS = {
        { "1",  "Invisible",      "Do not display if no handler" },
        { "2",  "Hidden",         "Do not display or print" },
        { "3",  "Print",          "Print when page is printed" },
        { "4",  "NoZoom",         "Do not scale with page zoom" },
        { "5",  "NoRotate",       "Do not rotate with page" },
        { "6",  "NoView",         "Do not display on screen (may still print)" },
        { "7",  "ReadOnly",       "Do not allow interaction" },
        { "8",  "Locked",         "Do not allow deletion or property changes" },
        { "9",  "ToggleNoView",   "Invert NoView flag for certain events" },
        { "10", "LockedContents", "Do not allow content changes" },
    };

    /** Common field flags (ISO 32000-2 §12.7.4.1, Table 226) */
    private static final String[][] FF_COMMON_BITS = {
        { "1",  "ReadOnly",   "Field value cannot be changed" },
        { "2",  "Required",   "Field must have a value before submit" },
        { "3",  "NoExport",   "Field value not exported by submit action" },
    };

    /** Text field flags (ISO 32000-2 §12.7.5.3) */
    private static final String[][] FF_TEXT_BITS = {
        { "1",  "ReadOnly",        "Field value cannot be changed" },
        { "2",  "Required",        "Field must have a value before submit" },
        { "3",  "NoExport",        "Field value not exported by submit action" },
        { "13", "Multiline",       "Text may span multiple lines" },
        { "14", "Password",        "Text is masked (password entry)" },
        { "21", "FileSelect",      "Value is a file path" },
        { "23", "DoNotSpellCheck", "Spell checking disabled" },
        { "24", "DoNotScroll",     "Text field does not scroll" },
        { "25", "Comb",            "Text divided into equally spaced cells" },
        { "26", "RichText",        "Value may contain rich text (XHTML)" },
    };

    /** Button field flags (ISO 32000-2 §12.7.5.2) */
    private static final String[][] FF_BUTTON_BITS = {
        { "1",  "ReadOnly",        "Field value cannot be changed" },
        { "2",  "Required",        "Field must have a value before submit" },
        { "3",  "NoExport",        "Field value not exported by submit action" },
        { "15", "NoToggleToOff",   "Radio: exactly one button must be selected at all times" },
        { "16", "Radio",           "This is a set of radio buttons (not checkboxes)" },
        { "17", "Pushbutton",      "Momentary push button (no retained value)" },
        { "26", "RadiosInUnison",  "Radio buttons with same value turn on/off together" },
    };

    /** Choice field flags (ISO 32000-2 §12.7.5.4) */
    private static final String[][] FF_CHOICE_BITS = {
        { "1",  "ReadOnly",           "Field value cannot be changed" },
        { "2",  "Required",           "Field must have a value before submit" },
        { "3",  "NoExport",           "Field value not exported by submit action" },
        { "18", "Combo",              "Combo box (drop-down); otherwise list box" },
        { "19", "Edit",               "Combo box allows custom text entry" },
        { "20", "Sort",               "Options should be sorted alphabetically" },
        { "22", "MultiSelect",        "Multiple items may be selected simultaneously" },
        { "23", "DoNotSpellCheck",    "Spell checking disabled" },
        { "27", "CommitOnSelChange",  "Value committed immediately on selection change" },
    };

    /**
     * Returns the appropriate flag bit definitions for a given field type.
     */
    private String[][] getFieldFlagBits(PDField field) {
        if (field instanceof PDTextField) return FF_TEXT_BITS;
        if (field instanceof PDPushButton || field instanceof PDCheckBox || field instanceof PDRadioButton)
            return FF_BUTTON_BITS;
        if (field instanceof PDComboBox || field instanceof PDListBox) return FF_CHOICE_BITS;
        return FF_COMMON_BITS;
    }

    /**
     * Formats a bitfield integer into a human-readable string showing the decimal value,
     * binary representation, and the meaning of each set bit.
     *
     * Example output: "3 (0b11) — SignaturesExist: Document contains at least one signature field |
     *                  AppendOnly: Document must be saved incrementally"
     *
     * @param value   the raw integer bitfield value
     * @param bitDefs array of {bitPosition (1-based), name, description} for all defined bits
     */
    private static String formatBitfield(int value, String[][] bitDefs) {
        if (value == 0) return "0 (none set)";

        // Find highest defined bit for binary display width
        int highBit = 0;
        for (String[] def : bitDefs) {
            int bit = Integer.parseInt(def[0]);
            if (bit > highBit) highBit = bit;
        }
        // Also consider any set bits beyond defined ones
        int msb = 32 - Integer.numberOfLeadingZeros(value);
        if (msb > highBit) highBit = msb;

        // Binary representation
        String binary = Integer.toBinaryString(value);
        StringBuilder sb = new StringBuilder();
        sb.append(value).append(" (0b").append(binary).append(") — ");

        // List set bits with descriptions
        List<String> setFlags = new ArrayList<>();
        for (String[] def : bitDefs) {
            int bit = Integer.parseInt(def[0]);
            boolean isSet = (value & (1 << (bit - 1))) != 0;
            if (isSet) {
                setFlags.add(def[1] + ": " + def[2]);
            }
        }
        if (setFlags.isEmpty()) {
            sb.append("(no recognized flags)");
        } else {
            sb.append(String.join(" | ", setFlags));
        }
        return sb.toString();
    }
}
