package io.pdfalyzer.service;

import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
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
        if (field.getValueAsString() != null && !field.getValueAsString().isEmpty())
            node.addProperty("Value", field.getValueAsString());
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
        if (field instanceof PDTextField) return "text";
        if (field instanceof PDCheckBox) return "checkbox";
        if (field instanceof PDRadioButton) return "radio";
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
