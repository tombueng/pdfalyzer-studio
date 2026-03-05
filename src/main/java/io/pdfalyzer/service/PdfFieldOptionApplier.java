package io.pdfalyzer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.stereotype.Component;

/**
 * Applies options and JavaScript validation to PDF form fields.
 * Called by {@link PdfEditService} and {@link PdfFormFieldBuilder}.
 */
@Component
public class PdfFieldOptionApplier {

    void applyOptionsToField(PDField field, Map<String, Object> options) throws IOException {
        if (options == null || options.isEmpty()) {
            return;
        }

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
