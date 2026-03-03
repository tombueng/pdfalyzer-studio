package io.pdfalyzer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.FormFieldRequest;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PdfEditService {

    public byte[] addFormField(byte[] pdfBytes, FormFieldRequest request) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                acroForm = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(acroForm);
                PDResources resources = new PDResources();
                resources.put(COSName.getPDFName("Helv"),
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                acroForm.setDefaultResources(resources);
                acroForm.setDefaultAppearance("/Helv 10 Tf 0 g");
            }
            acroForm.setNeedAppearances(true);

            if (request.getFieldName() == null || request.getFieldName().isBlank()) {
                throw new IllegalArgumentException("Field name is required");
            }
            if (request.getPageIndex() < 0 || request.getPageIndex() >= doc.getNumberOfPages()) {
                throw new IllegalArgumentException("Invalid page index: " + request.getPageIndex());
            }
            if (request.getWidth() <= 0 || request.getHeight() <= 0) {
                throw new IllegalArgumentException("Field width and height must be positive");
            }

            PDPage page = doc.getPage(request.getPageIndex());
            PDRectangle rect = new PDRectangle(
                    (float) request.getX(),
                    (float) request.getY(),
                    (float) request.getWidth(),
                    (float) request.getHeight()
            );

            String fieldType = request.getFieldType();
            if ("text".equals(fieldType) || "textarea".equals(fieldType)) {
                addTextField(doc, acroForm, page, rect, request);
            } else if ("checkbox".equals(fieldType)) {
                addCheckboxField(doc, acroForm, page, rect, request);
            } else if ("combo".equals(fieldType)) {
                addComboField(doc, acroForm, page, rect, request);
            } else if ("radio".equals(fieldType)) {
                addRadioField(doc, acroForm, page, rect, request);
            } else if ("signature".equals(fieldType)) {
                addSignatureField(doc, acroForm, page, rect, request);
            } else {
                throw new IllegalArgumentException("Unsupported field type: " + fieldType);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("Added {} field '{}' on page {}", fieldType, request.getFieldName(), request.getPageIndex());
            return out.toByteArray();
        }
    }

    public byte[] addFormFields(byte[] pdfBytes, List<FormFieldRequest> requests) throws IOException {
        if (requests == null || requests.isEmpty()) {
            return pdfBytes;
        }
        byte[] current = pdfBytes;
        for (FormFieldRequest request : requests) {
            current = addFormField(current, request);
        }
        return current;
    }

    private void addTextField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                              PDRectangle rect, FormFieldRequest request) throws IOException {
        PDTextField field = new PDTextField(acroForm);
        field.setPartialName(request.getFieldName());
        field.setDefaultAppearance("/Helv 10 Tf 0 g");

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        // Create appearance stream so the field is visible in all viewers
        PDAppearanceDictionary appearances = new PDAppearanceDictionary();
        PDAppearanceStream normalAppearance = new PDAppearanceStream(doc);
        normalAppearance.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));
        normalAppearance.setResources(acroForm.getDefaultResources());
        appearances.setNormalAppearance(normalAppearance);
        widget.setAppearance(appearances);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
        applyOptionsToField(field, request.getOptions());
    }

    private void addCheckboxField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                                   PDRectangle rect, FormFieldRequest request) throws IOException {
        PDCheckBox field = new PDCheckBox(acroForm);
        field.setPartialName(request.getFieldName());

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        // Create appearances for both checked and unchecked states
        PDAppearanceDictionary appearances = new PDAppearanceDictionary();
        
        // Unchecked appearance (empty box)
        PDAppearanceStream offAppearance = new PDAppearanceStream(doc);
        offAppearance.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));
        offAppearance.setResources(acroForm.getDefaultResources());
        try (PDPageContentStream cs = new PDPageContentStream(doc, offAppearance)) {
            cs.setStrokingColor(0f);
            cs.setLineWidth(1f);
            cs.addRect(0.5f, 0.5f, Math.max(1f, rect.getWidth() - 1f), Math.max(1f, rect.getHeight() - 1f));
            cs.stroke();
        }
        
        // Checked appearance (box with large checkmark)
        PDAppearanceStream onAppearance = new PDAppearanceStream(doc);
        onAppearance.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));
        onAppearance.setResources(acroForm.getDefaultResources());
        try (PDPageContentStream cs = new PDPageContentStream(doc, onAppearance)) {
            // Draw box border
            cs.setStrokingColor(0f);
            cs.setLineWidth(1f);
            cs.addRect(0.5f, 0.5f, Math.max(1f, rect.getWidth() - 1f), Math.max(1f, rect.getHeight() - 1f));
            cs.stroke();
            
            // Draw large checkmark that fills the box
            cs.setLineWidth(Math.max(2f, rect.getWidth() * 0.1f));
            
            float w = rect.getWidth();
            float h = rect.getHeight();
            float margin = Math.min(w, h) * 0.15f;
            
            // Draw checkmark with two lines
            cs.moveTo(margin, h * 0.45f);
            cs.lineTo(w * 0.4f, margin);
            cs.stroke();
            
            cs.moveTo(w * 0.4f, margin);
            cs.lineTo(w - margin, h - margin);
            cs.stroke();
        }
        
        // Set up appearance dictionary with named appearances
        org.apache.pdfbox.cos.COSDictionary normalAppDic = new org.apache.pdfbox.cos.COSDictionary();
        normalAppDic.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Off"), offAppearance.getCOSObject());
        normalAppDic.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Yes"), onAppearance.getCOSObject());
        appearances.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.N, normalAppDic);
        widget.setAppearance(appearances);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
        applyOptionsToField(field, request.getOptions());
    }

    private void addComboField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                                PDRectangle rect, FormFieldRequest request) throws IOException {
        PDComboBox field = new PDComboBox(acroForm);
        field.setPartialName(request.getFieldName());

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
        applyOptionsToField(field, request.getOptions());
    }

    private void addRadioField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                                PDRectangle rect, FormFieldRequest request) throws IOException {
        PDRadioButton field = new PDRadioButton(acroForm);
        field.setPartialName(request.getFieldName());

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        PDAppearanceDictionary appearances = new PDAppearanceDictionary();
        PDAppearanceStream normalAppearance = new PDAppearanceStream(doc);
        normalAppearance.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));
        normalAppearance.setResources(acroForm.getDefaultResources());
        appearances.setNormalAppearance(normalAppearance);
        widget.setAppearance(appearances);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
        applyOptionsToField(field, request.getOptions());
        field.setExportValues(java.util.Collections.singletonList("On"));
        field.setValue("Off");
    }

    private void addSignatureField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                                    PDRectangle rect, FormFieldRequest request) throws IOException {
        PDSignatureField field = new PDSignatureField(acroForm);
        field.setPartialName(request.getFieldName());

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        PDAppearanceDictionary appearances = new PDAppearanceDictionary();
        PDAppearanceStream normalAppearance = new PDAppearanceStream(doc);
        normalAppearance.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));
        normalAppearance.setResources(acroForm.getDefaultResources());
        try (PDPageContentStream cs = new PDPageContentStream(doc, normalAppearance)) {
            cs.setStrokingColor(0f);
            cs.setLineWidth(1f);
            cs.addRect(0.5f, 0.5f, Math.max(1f, rect.getWidth() - 1f), Math.max(1f, rect.getHeight() - 1f));
            cs.stroke();
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE), 9);
            cs.newLineAtOffset(4, Math.max(10, rect.getHeight() / 2));
            cs.showText("Sign here");
            cs.endText();
        }
        appearances.setNormalAppearance(normalAppearance);
        widget.setAppearance(appearances);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
        applyOptionsToField(field, request.getOptions());
    }

    /**
     * Delete a form field (and its widgets) from the document.
     *
     * @param pdfBytes  current PDF bytes
     * @param fieldName fully-qualified field name to remove
     * @return updated PDF bytes
     */
    public byte[] deleteFormField(byte[] pdfBytes, String fieldName) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) throw new IllegalArgumentException("No AcroForm in document");

            PDField target = findField(acroForm.getFields(), fieldName);
            if (target == null) throw new IllegalArgumentException("Field not found: " + fieldName);

            // Remove widget annotations from their pages (use COS identity comparison)
            for (org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget widget
                    : target.getWidgets()) {
                COSDictionary widgetCos = widget.getCOSObject();
                // Try page reference from widget
                PDPage page = widget.getPage();
                if (page == null) {
                    // Fallback: scan all pages
                    for (PDPage p : doc.getPages()) {
                        for (org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation a
                                : p.getAnnotations()) {
                            if (a.getCOSObject() == widgetCos) { page = p; break; }
                        }
                        if (page != null) break;
                    }
                }
                if (page != null) {
                    final PDPage finalPage = page;
                    java.util.List<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation>
                            annots = finalPage.getAnnotations();
                    annots.removeIf(a -> a.getCOSObject() == widgetCos);
                    finalPage.setAnnotations(annots);
                }
            }

            // Remove from AcroForm fields via direct COS array manipulation for reliability
            COSDictionary parentCos = target.getParent() != null
                    ? target.getParent().getCOSObject()
                    : acroForm.getCOSObject();
            COSName childKey = target.getParent() != null
                    ? COSName.getPDFName("Kids")
                    : COSName.FIELDS;
            COSBase arr = parentCos.getDictionaryObject(childKey);
            if (arr instanceof COSArray) {
                COSArray cosArr = (COSArray) arr;
                COSDictionary targetCos = target.getCOSObject();
                for (int i = cosArr.size() - 1; i >= 0; i--) {
                    COSBase item = cosArr.getObject(i);
                    if (item == targetCos) { cosArr.remove(i); break; }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("Deleted form field '{}'", fieldName);
            return out.toByteArray();
        }
    }

    /**
     * Set the value of a form field.
     */
    public byte[] setFormFieldValue(byte[] pdfBytes, String fieldName, String value) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) throw new IllegalArgumentException("No AcroForm in document");
            PDField target = findField(acroForm.getFields(), fieldName);
            if (target == null) throw new IllegalArgumentException("Field not found: " + fieldName);
            target.setValue(value);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("Set field '{}' value to '{}'", fieldName, value);
            return out.toByteArray();
        }
    }

    /**
     * Update the list of choices for a combo-box / list-box field.
     */
    public byte[] setComboChoices(byte[] pdfBytes, String fieldName,
                                   java.util.List<String> choices) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) throw new IllegalArgumentException("No AcroForm in document");
            PDField target = findField(acroForm.getFields(), fieldName);
            if (!(target instanceof PDComboBox) && !(target instanceof PDListBox)) {
                throw new IllegalArgumentException("Field is not a combo/list box: " + fieldName);
            }
            if (target instanceof PDComboBox) ((PDComboBox) target).setOptions(choices);
            else ((PDListBox) target).setOptions(choices);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("Updated choices for field '{}': {}", fieldName, choices);
            return out.toByteArray();
        }
    }

    public byte[] updateFieldRect(byte[] pdfBytes, String fieldName,
                                   double x, double y, double width, double height) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) throw new IllegalArgumentException("No AcroForm in document");
            PDField target = findField(acroForm.getFields(), fieldName);
            if (target == null) throw new IllegalArgumentException("Field not found: " + fieldName);
            if (target.getWidgets().isEmpty()) throw new IllegalArgumentException("Field has no widget: " + fieldName);

            PDRectangle rect = new PDRectangle((float) x, (float) y, (float) width, (float) height);
            for (PDAnnotationWidget widget : target.getWidgets()) {
                widget.setRectangle(rect);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("Updated rectangle for field '{}' to [{}, {}, {}, {}]", fieldName, x, y, width, height);
            return out.toByteArray();
        }
    }

    public byte[] applyFieldOptions(byte[] pdfBytes,
                                    List<String> fieldNames,
                                    Map<String, Object> options) throws IOException {
        if (fieldNames == null || fieldNames.isEmpty() || options == null || options.isEmpty()) {
            return pdfBytes;
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) throw new IllegalArgumentException("No AcroForm in document");

            for (String fieldName : fieldNames) {
                PDField target = findField(acroForm.getFields(), fieldName);
                if (target != null) {
                    applyOptionsToField(target, options);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("Applied options {} to {} fields", options.keySet(), fieldNames.size());
            return out.toByteArray();
        }
    }

    private void applyOptionsToField(PDField field, Map<String, Object> options) throws IOException {
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

    private void setValidationJavaScript(PDField field, String script) {
        COSDictionary fieldDictionary = field.getCOSObject();
        COSBase aaBase = fieldDictionary.getDictionaryObject(COSName.AA);
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

    private Boolean parseTriState(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        String s = value.toString().trim().toLowerCase();
        if (s.isEmpty() || "keep".equals(s) || "mixed".equals(s) || "null".equals(s)) return null;
        if ("true".equals(s)) return Boolean.TRUE;
        if ("false".equals(s)) return Boolean.FALSE;
        return null;
    }

    private List<String> parseChoiceList(Object rawValue) {
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

    private PDField findField(java.util.List<PDField> fields, String name) {
        for (PDField f : fields) {
            if (name.equals(f.getFullyQualifiedName())) return f;
            if (name.equals(f.getPartialName())) return f;
            if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField) {
                PDField found = findField(
                        ((org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField) f).getChildren(),
                        name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
