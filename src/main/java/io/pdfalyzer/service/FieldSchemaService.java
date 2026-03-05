package io.pdfalyzer.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import io.pdfalyzer.model.FieldPropertyDescriptor;

/**
 * Provides the field property schema used by the frontend to build the generic field dialog.
 * Each descriptor defines a property key, its control type, tab group, valid values,
 * and which field types support it.
 */
@Service
public class FieldSchemaService {

    private static final List<FieldPropertyDescriptor> SCHEMA = buildSchema();

    public List<FieldPropertyDescriptor> getSchema() {
        return SCHEMA;
    }

    private static List<FieldPropertyDescriptor> buildSchema() {
        List<FieldPropertyDescriptor> schema = new ArrayList<>();

        // ── General tab ─────────────────────────────────────────────────────────────
        schema.add(prop("fieldName",  "Field Name",  "text",   "general", null, null, null, true));
        schema.add(prop("fieldType",  "Field Type",  "text",   "general", null, null, null, true));
        schema.add(prop("pageIndex",  "Page",        "number", "general", null, null, null, true));
        schema.add(prop("required",   "Required",    "boolean","general", false, null, null, false));
        schema.add(prop("readonly",   "Read Only",   "boolean","general", false, null, null, false));

        // ── Appearance tab ───────────────────────────────────────────────────────────
        schema.add(prop("fontName", "Font", "select", "appearance", "Helv",
                List.of("Helv", "HeBo", "HeOb", "HeBo", "TiRo", "TiBo", "TiIt", "TiBo",
                        "Cour", "CoBo", "CoOb", "CoBo", "Symbol", "ZaDb"),
                null, false));
        schema.add(prop("fontSize",         "Font Size",       "number", "appearance", 10,    null,                                                     null, false));
        schema.add(prop("textColor",        "Text Color",      "color",  "appearance", "#000000", null,                                                  null, false));
        schema.add(prop("backgroundColor",  "Background",      "color",  "appearance", null,  null,                                                     null, false));
        schema.add(prop("borderColor",      "Border Color",    "color",  "appearance", "#000000", null,                                                  null, false));
        schema.add(prop("borderWidth",      "Border Width",    "number", "appearance", 1,     null,                                                     null, false));
        schema.add(prop("borderStyle",      "Border Style",    "select", "appearance", "solid",
                List.of("solid", "dashed", "beveled", "inset", "underline"),
                null, false));
        schema.add(prop("alignment",        "Text Alignment",  "select", "appearance", "left",
                List.of("left", "center", "right"),
                List.of("text", "combo", "list"), false));
        schema.add(prop("rotation",         "Rotation",        "select", "appearance", "0",
                List.of("0", "90", "180", "270"),
                null, false));

        // ── Options tab ──────────────────────────────────────────────────────────────
        schema.add(prop("multiline",    "Multiline",       "boolean", "options", false, null, List.of("text"),           false));
        schema.add(prop("maxLength",    "Max Length",      "number",  "options", null,  null, List.of("text"),           false));
        schema.add(prop("editable",     "Editable",        "boolean", "options", false, null, List.of("combo"),          false));
        schema.add(prop("multiSelect",  "Multi-Select",    "boolean", "options", false, null, List.of("list"),           false));
        schema.add(prop("checked",      "Initially Checked","boolean","options", false, null, List.of("checkbox"),       false));
        schema.add(prop("defaultValue", "Default Value",   "text",    "options", null,  null, List.of("text","combo","list"), false));
        schema.add(prop("choices",      "Items",           "choiceTable", "options", null, null, List.of("combo","list"), false));
        schema.add(prop("radioOptions", "Radio Options",   "radioTable",  "options", null, null, List.of("radio"),       false));

        // ── Script tab ───────────────────────────────────────────────────────────────
        schema.add(prop("javascript", "Validation Script", "script", "script", null, null, null, false));

        return List.copyOf(schema);
    }

    private static FieldPropertyDescriptor prop(String key, String label, String controlType,
            String group, Object defaultValue, List<String> validValues,
            List<String> supportedTypes, boolean readOnly) {
        return FieldPropertyDescriptor.builder()
                .key(key)
                .label(label)
                .controlType(controlType)
                .group(group)
                .defaultValue(defaultValue)
                .validValues(validValues != null ? validValues : List.of())
                .supportedTypes(supportedTypes != null ? supportedTypes : List.of())
                .readOnly(readOnly)
                .build();
    }
}
