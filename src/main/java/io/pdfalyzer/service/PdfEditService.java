package io.pdfalyzer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.FormFieldRequest;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PdfEditService {

    private final PdfFormFieldBuilder fieldBuilder;
    private final PdfFieldOptionApplier optionApplier;

    public PdfEditService(PdfFormFieldBuilder fieldBuilder, PdfFieldOptionApplier optionApplier) {
        this.fieldBuilder = fieldBuilder;
        this.optionApplier = optionApplier;
    }

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
                fieldBuilder.addTextField(doc, acroForm, page, rect, request);
            } else if ("checkbox".equals(fieldType)) {
                fieldBuilder.addCheckboxField(doc, acroForm, page, rect, request);
            } else if ("combo".equals(fieldType)) {
                fieldBuilder.addComboField(doc, acroForm, page, rect, request);
            } else if ("radio".equals(fieldType)) {
                fieldBuilder.addRadioField(doc, acroForm, page, rect, request);
            } else if ("signature".equals(fieldType)) {
                fieldBuilder.addSignatureField(doc, acroForm, page, rect, request);
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

    public byte[] deleteFormField(byte[] pdfBytes, String fieldName) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) throw new IllegalArgumentException("No AcroForm in document");

            PDField target = findField(acroForm.getFields(), fieldName);
            if (target == null) throw new IllegalArgumentException("Field not found: " + fieldName);

            for (PDAnnotationWidget widget : target.getWidgets()) {
                COSDictionary widgetCos = widget.getCOSObject();
                PDPage page = widget.getPage();
                if (page == null) {
                    for (PDPage p : doc.getPages()) {
                        for (org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation a : p.getAnnotations()) {
                            if (a.getCOSObject() == widgetCos) { page = p; break; }
                        }
                        if (page != null) break;
                    }
                }
                if (page != null) {
                    final PDPage finalPage = page;
                    List<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation> annots = finalPage.getAnnotations();
                    annots.removeIf(a -> a.getCOSObject() == widgetCos);
                    finalPage.setAnnotations(annots);
                }
            }

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

    public byte[] setComboChoices(byte[] pdfBytes, String fieldName,
                                   List<String> choices) throws IOException {
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
                    optionApplier.applyOptionsToField(target, options);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("Applied options {} to {} fields", options.keySet(), fieldNames.size());
            return out.toByteArray();
        }
    }

    PDField findField(List<PDField> fields, String name) {
        for (PDField f : fields) {
            if (name.equals(f.getFullyQualifiedName())) return f;
            if (name.equals(f.getPartialName())) return f;
            if (f instanceof PDNonTerminalField) {
                PDField found = findField(((PDNonTerminalField) f).getChildren(), name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
