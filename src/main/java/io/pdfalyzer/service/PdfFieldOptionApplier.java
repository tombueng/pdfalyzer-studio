package io.pdfalyzer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies options and JavaScript validation to PDF form fields.
 * Called by {@link PdfEditService} and {@link PdfFormFieldBuilder}.
 */
@Component
@Slf4j
public class PdfFieldOptionApplier {

    void applyOptionsToField(PDField field, Map<String, Object> options) throws IOException {
        if (options == null || options.isEmpty()) {
            return;
        }
        applyAppearanceOptions(field, options);

        Boolean required = parseTriState(options.get("required"));
        if (required != null) field.setRequired(required);

        Boolean readonly = parseTriState(options.get("readonly"));
        if (readonly != null) field.setReadOnly(readonly);

        if (field instanceof PDTextField) {
            PDTextField textField = (PDTextField) field;
            Boolean multiline = parseTriState(options.get("multiline"));
            if (multiline != null) textField.setMultiline(multiline);
        }

        if (field instanceof PDComboBox) {
            PDComboBox comboBox = (PDComboBox) field;
            Boolean editable = parseTriState(options.get("editable"));
            if (editable != null) comboBox.setEdit(editable);
            applyChoiceOptions(comboBox, options.get("choices"));
        }

        if (field instanceof PDListBox) {
            PDListBox listBox = (PDListBox) field;
            applyChoiceOptions(listBox, options.get("choices"));
            Boolean multiSelect = parseTriState(options.get("multiSelect"));
            if (multiSelect != null) {
                int ff = listBox.getCOSObject().getInt(COSName.FF, 0);
                if (multiSelect) ff |= (1 << 21);
                else ff &= ~(1 << 21);
                listBox.getCOSObject().setInt(COSName.FF, ff);
            }
        }

        if (field instanceof PDCheckBox) {
            PDCheckBox checkBox = (PDCheckBox) field;
            Boolean checked = parseTriState(options.get("checked"));
            if (checked != null) {
                if (checked) checkBox.check();
                else checkBox.unCheck();
            }
        }

        if (options.containsKey("defaultValue")) {
            Object valueObj = options.get("defaultValue");
            if (valueObj != null) {
                String value = valueObj.toString();
                if (!value.isBlank()) field.setValue(value);
            }
        }

        if (options.containsKey("javascript")) {
            Object jsObj = options.get("javascript");
            setValidationJavaScript(field, jsObj == null ? "" : jsObj.toString());
        }
    }

    void setValidationJavaScript(PDField field, String script) {
        COSDictionary fieldDictionary = field.getCOSObject();
        org.apache.pdfbox.cos.COSBase aaBase = fieldDictionary.getDictionaryObject(COSName.AA);
        COSDictionary additionalActions = aaBase instanceof COSDictionary
                ? (COSDictionary) aaBase
                : null;

        if (script == null || script.isBlank()) {
            if (additionalActions != null) {
                additionalActions.removeItem(COSName.V);
                if (additionalActions.size() == 0) {
                    fieldDictionary.removeItem(COSName.AA);
                }
            }
            return;
        }

        if (additionalActions == null) {
            additionalActions = new COSDictionary();
            fieldDictionary.setItem(COSName.AA, additionalActions);
        }

        COSDictionary validateAction = new COSDictionary();
        validateAction.setName(COSName.S, "JavaScript");
        validateAction.setString(COSName.JS, script);
        additionalActions.setItem(COSName.V, validateAction);
    }

    /**
     * Applies appearance-related options (font, colors, border, alignment, maxLength)
     * to a field's COS dictionary and widget MK/BS entries.
     */
    void applyAppearanceOptions(PDField field, Map<String, Object> options) {
        boolean hasFontChange = options.containsKey("fontName")
                || options.containsKey("fontSize")
                || options.containsKey("textColor");
        if (hasFontChange && field instanceof PDVariableText vt) {
            String current = vt.getDefaultAppearance();
            // All three cleared → remove DA entirely (field inherits from AcroForm DR)
            boolean allCleared = options.containsKey("fontName") && options.containsKey("fontSize") && options.containsKey("textColor")
                    && (options.get("fontName") == null || options.get("fontName").toString().isBlank())
                    && (options.get("fontSize") == null || options.get("fontSize").toString().isBlank())
                    && (options.get("textColor") == null || options.get("textColor").toString().isBlank());
            if (allCleared) {
                vt.setDefaultAppearance("");
            } else {
                if (current == null || current.isBlank()) current = "/Helv 10 Tf 0 g";
                vt.setDefaultAppearance(rebuildDa(current, options));
            }
        }

        if (options.containsKey("alignment")) {
            Object alignment = options.get("alignment");
            if (alignment == null || alignment.toString().isBlank()) {
                field.getCOSObject().removeItem(COSName.Q);
            } else {
                int q = switch (alignment.toString().toLowerCase()) {
                    case "center" -> 1;
                    case "right" -> 2;
                    default -> 0;
                };
                field.getCOSObject().setInt(COSName.Q, q);
            }
        }

        Object maxLength = options.get("maxLength");
        if (maxLength != null && field instanceof PDTextField tf) {
            try {
                int ml = Integer.parseInt(maxLength.toString().trim());
                if (ml > 0) tf.setMaxLen(ml);
                else tf.getCOSObject().removeItem(COSName.getPDFName("MaxLen"));
            } catch (NumberFormatException e) {
                log.debug("Invalid maxLength: {}", maxLength);
            }
        }

        boolean hasBorderOrBg = options.containsKey("borderColor")
                || options.containsKey("backgroundColor")
                || options.containsKey("borderWidth")
                || options.containsKey("borderStyle")
                || options.containsKey("rotation");
        if (hasBorderOrBg) {
            for (PDAnnotationWidget widget : field.getWidgets()) {
                applyWidgetAppearance(widget, options);
            }
        }
    }

    /** Rebuilds the DA string, substituting only the keys present in options. */
    private String rebuildDa(String current, Map<String, Object> options) {
        // Extract current font name + size
        String fontName = "Helv";
        String fontSize = "10";
        String colorOp = "0 g";
        java.util.regex.Matcher tfm = java.util.regex.Pattern
                .compile("/([\\w+]+)\\s+(\\d+(?:\\.\\d+)?)\\s+Tf").matcher(current);
        if (tfm.find()) { fontName = tfm.group(1); fontSize = tfm.group(2); }
        java.util.regex.Matcher gm = java.util.regex.Pattern
                .compile("([\\d.]+)\\s+g(?:\\s|$)").matcher(current);
        if (gm.find()) colorOp = gm.group(1) + " g";
        java.util.regex.Matcher rgbm = java.util.regex.Pattern
                .compile("([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+rg(?:\\s|$)").matcher(current);
        if (rgbm.find()) colorOp = rgbm.group(1) + " " + rgbm.group(2) + " " + rgbm.group(3) + " rg";

        Object fn = options.get("fontName");
        if (fn != null && !fn.toString().isBlank()) fontName = fn.toString().trim();
        Object fs = options.get("fontSize");
        if (fs != null && !fs.toString().isBlank()) {
            try { Float.parseFloat(fs.toString()); fontSize = fs.toString().trim(); }
            catch (NumberFormatException e) { log.debug("Invalid fontSize: {}", fs); }
        }
        Object tc = options.get("textColor");
        if (tc != null) {
            String hex = tc.toString().trim();
            if (hex.matches("#[0-9a-fA-F]{6}")) {
                int r = Integer.parseInt(hex.substring(1, 3), 16);
                int g = Integer.parseInt(hex.substring(3, 5), 16);
                int b = Integer.parseInt(hex.substring(5, 7), 16);
                colorOp = (r == 0 && g == 0 && b == 0) ? "0 g"
                        : String.format("%.4f %.4f %.4f rg", r / 255f, g / 255f, b / 255f);
            }
        }
        return "/" + fontName + " " + fontSize + " Tf " + colorOp;
    }

    /** Applies (or removes) border color/width/style, background color, and rotation to a single widget. */
    private void applyWidgetAppearance(PDAnnotationWidget widget, Map<String, Object> options) {
        COSDictionary widgetDict = widget.getCOSObject();

        // MK dictionary: borderColor, backgroundColor, rotation
        boolean needsMk = options.containsKey("borderColor") || options.containsKey("backgroundColor")
                || options.containsKey("rotation");
        if (needsMk) {
            COSDictionary mk = (COSDictionary) widgetDict.getDictionaryObject(COSName.MK);
            if (options.containsKey("borderColor")) {
                Object bc = options.get("borderColor");
                if (bc == null || bc.toString().isBlank()) {
                    if (mk != null) mk.removeItem(COSName.BC);
                } else {
                    if (mk == null) { mk = new COSDictionary(); widgetDict.setItem(COSName.MK, mk); }
                    COSArray arr = hexToFloatArray(bc.toString()); if (arr != null) mk.setItem(COSName.BC, arr);
                }
            }
            if (options.containsKey("backgroundColor")) {
                Object bg = options.get("backgroundColor");
                if (bg == null || bg.toString().isBlank()) {
                    if (mk != null) mk.removeItem(COSName.BG);
                } else {
                    if (mk == null) { mk = new COSDictionary(); widgetDict.setItem(COSName.MK, mk); }
                    COSArray arr = hexToFloatArray(bg.toString()); if (arr != null) mk.setItem(COSName.BG, arr);
                }
            }
            if (options.containsKey("rotation")) {
                Object rot = options.get("rotation");
                if (mk == null) { mk = new COSDictionary(); widgetDict.setItem(COSName.MK, mk); }
                if (rot == null || rot.toString().isBlank()) {
                    mk.removeItem(COSName.getPDFName("R"));
                } else {
                    try { mk.setInt(COSName.getPDFName("R"), Integer.parseInt(rot.toString().trim())); }
                    catch (NumberFormatException e) { log.debug("Invalid rotation: {}", rot); }
                }
            }
        }

        // BS dictionary: borderWidth, borderStyle
        boolean needsBs = options.containsKey("borderWidth") || options.containsKey("borderStyle");
        if (needsBs) {
            COSDictionary bsDict = (COSDictionary) widgetDict.getDictionaryObject(COSName.BS);
            if (options.containsKey("borderWidth")) {
                Object bw = options.get("borderWidth");
                if (bw == null || bw.toString().isBlank()) {
                    if (bsDict != null) bsDict.removeItem(COSName.W);
                } else {
                    if (bsDict == null) { bsDict = new COSDictionary(); widgetDict.setItem(COSName.BS, bsDict); }
                    try { bsDict.setItem(COSName.W, new COSFloat(Float.parseFloat(bw.toString().trim()))); }
                    catch (NumberFormatException e) { log.debug("Invalid borderWidth: {}", bw); }
                }
            }
            if (options.containsKey("borderStyle")) {
                Object bs = options.get("borderStyle");
                if (bs == null || bs.toString().isBlank()) {
                    if (bsDict != null) bsDict.removeItem(COSName.S);
                } else {
                    if (bsDict == null) { bsDict = new COSDictionary(); widgetDict.setItem(COSName.BS, bsDict); }
                    String styleCode = switch (bs.toString().toLowerCase()) {
                        case "dashed" -> "D";
                        case "beveled" -> "B";
                        case "inset" -> "I";
                        case "underline" -> "U";
                        default -> "S";
                    };
                    bsDict.setName(COSName.S, styleCode);
                }
            }
        }
    }

    /** Converts "#RRGGBB" to a COSArray of three normalized float values. Returns null on invalid input. */
    private COSArray hexToFloatArray(String hex) {
        if (hex == null) return null;
        hex = hex.trim();
        if (!hex.matches("#[0-9a-fA-F]{6}")) return null;
        COSArray arr = new COSArray();
        arr.add(new COSFloat(Integer.parseInt(hex.substring(1, 3), 16) / 255f));
        arr.add(new COSFloat(Integer.parseInt(hex.substring(3, 5), 16) / 255f));
        arr.add(new COSFloat(Integer.parseInt(hex.substring(5, 7), 16) / 255f));
        return arr;
    }

    Boolean parseTriState(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        String s = value.toString().trim().toLowerCase();
        if (s.isEmpty() || "keep".equals(s) || "mixed".equals(s) || "null".equals(s)) return null;
        if ("true".equals(s)) return Boolean.TRUE;
        if ("false".equals(s)) return Boolean.FALSE;
        return null;
    }

    /** Applies choice options (simple strings or {value,label} maps) to a combo or list field. */
    void applyChoiceOptions(PDField field, Object rawChoices) throws IOException {
        if (rawChoices == null) return;
        List<String> exportValues = new ArrayList<>();
        List<String> displayValues = new ArrayList<>();
        boolean hasPairs = false;

        if (rawChoices instanceof List<?>) {
            for (Object item : (List<?>) rawChoices) {
                if (item == null) continue;
                if (item instanceof Map<?, ?> m) {
                    String val = m.containsKey("value") ? m.get("value").toString().trim() : "";
                    String lbl = m.containsKey("label") ? m.get("label").toString().trim() : "";
                    if (val.isBlank()) continue;
                    exportValues.add(val);
                    displayValues.add(lbl.isBlank() ? val : lbl);
                    if (!lbl.isBlank() && !lbl.equals(val)) hasPairs = true;
                } else {
                    String s = item.toString().trim();
                    if (!s.isBlank()) { exportValues.add(s); displayValues.add(s); }
                }
            }
        } else {
            String csv = rawChoices.toString();
            for (String part : csv.split(",")) {
                String s = part.trim();
                if (!s.isBlank()) { exportValues.add(s); displayValues.add(s); }
            }
        }

        if (exportValues.isEmpty()) return;

        if (hasPairs) {
            COSArray opt = new COSArray();
            for (int i = 0; i < exportValues.size(); i++) {
                COSArray pair = new COSArray();
                pair.add(new COSString(exportValues.get(i)));
                pair.add(new COSString(displayValues.get(i)));
                opt.add(pair);
            }
            field.getCOSObject().setItem(COSName.OPT, opt);
        } else {
            if (field instanceof PDComboBox) ((PDComboBox) field).setOptions(exportValues);
            else if (field instanceof PDListBox) ((PDListBox) field).setOptions(exportValues);
        }
    }

    List<String> parseChoiceList(Object rawValue) {
        List<String> out = new ArrayList<>();
        if (rawValue == null) {
            return out;
        }
        if (rawValue instanceof List<?>) {
            List<?> rawChoices = (List<?>) rawValue;
            for (Object rawChoice : rawChoices) {
                if (rawChoice == null) continue;
                String choice = rawChoice.toString().trim();
                if (!choice.isBlank()) out.add(choice);
            }
            return out;
        }
        String csv = rawValue.toString();
        if (csv.isBlank()) {
            return out;
        }
        String[] parts = csv.split(",");
        for (String part : parts) {
            String choice = part == null ? "" : part.trim();
            if (!choice.isBlank()) out.add(choice);
        }
        return out;
    }
}
