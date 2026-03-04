package io.pdfalyzer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
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
            List<String> choices = parseChoiceList(options.get("choices"));
            if (!choices.isEmpty()) comboBox.setOptions(choices);
        }

        if (field instanceof PDListBox) {
            PDListBox listBox = (PDListBox) field;
            List<String> choices = parseChoiceList(options.get("choices"));
            if (!choices.isEmpty()) listBox.setOptions(choices);
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
