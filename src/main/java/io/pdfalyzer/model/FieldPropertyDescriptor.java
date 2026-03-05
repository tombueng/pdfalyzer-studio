package io.pdfalyzer.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class FieldPropertyDescriptor {

    /** Backend option key, e.g. "fontSize" */
    private String key;

    /** Human-readable label shown in the dialog, e.g. "Font Size" */
    private String label;

    /**
     * Control type used by the frontend to choose the right HTML template.
     * One of: boolean | number | text | color | select | choiceTable | radioTable | script
     */
    private String controlType;

    /**
     * Tab group for the dialog.
     * One of: general | appearance | options | script
     */
    private String group;

    /** Default value for new fields; null means no default pre-filled */
    private Object defaultValue;

    /** Valid values for "select" control type */
    private List<String> validValues;

    /**
     * Field types that show this property.
     * Empty list means the property applies to all field types.
     * Values: text | checkbox | combo | list | radio | signature
     */
    private List<String> supportedTypes;

    /** When true, the control is shown but not editable (e.g., name, type, page) */
    private boolean readOnly;
}
