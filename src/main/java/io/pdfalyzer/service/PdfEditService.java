package io.pdfalyzer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
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

    private PDAcroForm ensureAcroForm(PDDocument doc) {
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
        return acroForm;
    }

    private void addSingleFieldToDoc(PDDocument doc, PDAcroForm acroForm,
                                      FormFieldRequest request) throws IOException {
        if (request.getFieldName() == null || request.getFieldName().isBlank())
            throw new IllegalArgumentException("Field name is required");
        if (request.getPageIndex() < 0 || request.getPageIndex() >= doc.getNumberOfPages())
            throw new IllegalArgumentException("Invalid page index: " + request.getPageIndex());
        if (request.getWidth() <= 0 || request.getHeight() <= 0)
            throw new IllegalArgumentException("Field width and height must be positive");

        PDPage page = doc.getPage(request.getPageIndex());
        PDRectangle rect = new PDRectangle(
                (float) request.getX(), (float) request.getY(),
                (float) request.getWidth(), (float) request.getHeight());

        String fieldType = request.getFieldType();
        if ("text".equals(fieldType) || "textarea".equals(fieldType)) {
            fieldBuilder.addTextField(doc, acroForm, page, rect, request);
        } else if ("checkbox".equals(fieldType)) {
            fieldBuilder.addCheckboxField(doc, acroForm, page, rect, request);
        } else if ("combo".equals(fieldType)) {
            fieldBuilder.addComboField(doc, acroForm, page, rect, request);
        } else if ("list".equals(fieldType)) {
            fieldBuilder.addListField(doc, acroForm, page, rect, request);
        } else if ("signature".equals(fieldType)) {
            fieldBuilder.addSignatureField(doc, acroForm, page, rect, request);
        } else {
            throw new IllegalArgumentException("Unsupported field type: " + fieldType);
        }
        log.info("Added {} field '{}' on page {}", fieldType, request.getFieldName(), request.getPageIndex());
    }

    public byte[] addFormField(byte[] pdfBytes, FormFieldRequest request) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = ensureAcroForm(doc);
            if ("radio".equals(request.getFieldType())) {
                fieldBuilder.addRadioGroup(doc, acroForm, List.of(request));
            } else {
                addSingleFieldToDoc(doc, acroForm, request);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    public byte[] addFormFields(byte[] pdfBytes, List<FormFieldRequest> requests) throws IOException {
        if (requests == null || requests.isEmpty()) return pdfBytes;
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = ensureAcroForm(doc);
            Map<String, List<FormFieldRequest>> radioGroups = new LinkedHashMap<>();
            for (FormFieldRequest r : requests) {
                if ("radio".equals(r.getFieldType())) {
                    radioGroups.computeIfAbsent(r.getFieldName(), k -> new ArrayList<>()).add(r);
                } else {
                    addSingleFieldToDoc(doc, acroForm, r);
                }
            }
            for (List<FormFieldRequest> group : radioGroups.values()) {
                fieldBuilder.addRadioGroup(doc, acroForm, group);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("Added {} fields ({} radio group(s))", requests.size(), radioGroups.size());
            return out.toByteArray();
        }
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

    public byte[] restructureRadioGroup(byte[] pdfBytes, String fieldName,
                                         List<Map<String, String>> newOptions,
                                         Map<String, Object> fieldOptions) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) throw new IllegalArgumentException("No AcroForm in document");
            PDField target = findField(acroForm.getFields(), fieldName);
            if (!(target instanceof org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton group))
                throw new IllegalArgumentException("Not a radio button group: " + fieldName);

            // Map existing export values → widget COSDictionary + their pages
            Map<String, COSDictionary> existingByValue = new LinkedHashMap<>();
            Map<COSDictionary, PDPage> widgetPageMap = new LinkedHashMap<>();
            float[] firstRectData = null;
            PDPage firstPage = null;

            for (PDAnnotationWidget w : group.getWidgets()) {
                COSDictionary wDict = w.getCOSObject();
                COSBase apBase = wDict.getDictionaryObject(COSName.AP);
                if (!(apBase instanceof COSDictionary apDict)) continue;
                COSBase nBase = apDict.getDictionaryObject(COSName.N);
                if (!(nBase instanceof COSDictionary nDict)) continue;
                for (COSName key : nDict.keySet()) {
                    if (!"Off".equals(key.getName())) {
                        existingByValue.put(key.getName(), wDict);
                        PDPage pg = w.getPage();
                        if (pg != null) widgetPageMap.put(wDict, pg);
                        if (firstRectData == null) {
                            PDRectangle r = w.getRectangle();
                            if (r != null) {
                                firstRectData = new float[]{r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight()};
                                firstPage = pg;
                            }
                        }
                        break;
                    }
                }
            }
            if (firstPage == null) firstPage = doc.getPage(0);
            final float fx = firstRectData != null ? firstRectData[0] : 50f;
            final float fy = firstRectData != null ? firstRectData[1] : 50f;
            final float fw = firstRectData != null ? firstRectData[2] : 20f;
            final float fh = firstRectData != null ? firstRectData[3] : 14f;

            // Determine which values are kept vs removed
            Set<String> newValues = new LinkedHashSet<>();
            for (Map<String, String> opt : newOptions) {
                String v = opt.getOrDefault("value", "").trim();
                if (!v.isBlank()) newValues.add(v);
            }

            // Remove widgets for dropped values from their pages
            for (Map.Entry<String, COSDictionary> e : existingByValue.entrySet()) {
                if (!newValues.contains(e.getKey())) {
                    PDPage pg = widgetPageMap.get(e.getValue());
                    if (pg != null) {
                        final COSDictionary dc = e.getValue();
                        List<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation> annots = pg.getAnnotations();
                        annots.removeIf(a -> a.getCOSObject() == dc);
                        pg.setAnnotations(annots);
                    }
                }
            }

            // Build new Kids array
            COSArray newKids = new COSArray();
            for (Map<String, String> opt : newOptions) {
                String val = opt.getOrDefault("value", "").trim();
                if (val.isBlank()) continue;
                if (existingByValue.containsKey(val)) {
                    newKids.add(existingByValue.get(val));
                } else {
                    PDRectangle bbox = new PDRectangle(fw, fh);
                    PDAppearanceStream offS = new PDAppearanceStream(doc);
                    offS.setBBox(bbox); offS.setResources(acroForm.getDefaultResources());
                    PDAppearanceStream onS = new PDAppearanceStream(doc);
                    onS.setBBox(bbox); onS.setResources(acroForm.getDefaultResources());
                    COSDictionary nDict = new COSDictionary();
                    nDict.setItem(COSName.getPDFName("Off"), offS.getCOSObject());
                    nDict.setItem(COSName.getPDFName(val), onS.getCOSObject());
                    COSDictionary apDict = new COSDictionary();
                    apDict.setItem(COSName.N, nDict);
                    COSDictionary wDict = new COSDictionary();
                    wDict.setItem(COSName.TYPE, COSName.ANNOT);
                    wDict.setItem(COSName.SUBTYPE, COSName.getPDFName("Widget"));
                    wDict.setItem(COSName.PARENT, group.getCOSObject());
                    wDict.setItem(COSName.AS, COSName.getPDFName("Off"));
                    wDict.setItem(COSName.AP, apDict);
                    PDAnnotationWidget widget = new PDAnnotationWidget(wDict);
                    widget.setRectangle(new PDRectangle(fx, fy, fw, fh));
                    widget.setPage(firstPage); widget.setPrinted(true);
                    firstPage.getAnnotations().add(widget);
                    newKids.add(wDict);
                }
            }
            group.getCOSObject().setItem(COSName.KIDS, newKids);

            if (fieldOptions != null && !fieldOptions.isEmpty()) {
                // promote radioDefault → defaultValue
                Map<String, Object> opts = new LinkedHashMap<>(fieldOptions);
                Object rd = opts.remove("radioDefault");
                if (rd != null && !String.valueOf(rd).isBlank()) opts.put("defaultValue", rd);
                optionApplier.applyOptionsToField(group, opts);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("Restructured radio group '{}': {} options", fieldName, newValues.size());
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
