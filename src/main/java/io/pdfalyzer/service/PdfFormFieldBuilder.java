package io.pdfalyzer.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.stereotype.Component;

import io.pdfalyzer.model.FormFieldRequest;

/**
 * Creates individual form field types in a PDF document.
 * Called by {@link PdfEditService}.
 */
@Component
public class PdfFormFieldBuilder {

    private final PdfFieldOptionApplier optionApplier;

    public PdfFormFieldBuilder(PdfFieldOptionApplier optionApplier) {
        this.optionApplier = optionApplier;
    }

    void addTextField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                      PDRectangle rect, FormFieldRequest request) throws IOException {
        PDTextField field = new PDTextField(acroForm);
        field.setPartialName(request.getFieldName());
        field.setDefaultAppearance("/Helv 10 Tf 0 g");

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
        optionApplier.applyOptionsToField(field, request.getOptions());
    }

    void addCheckboxField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                          PDRectangle rect, FormFieldRequest request) throws IOException {
        PDCheckBox field = new PDCheckBox(acroForm);
        field.setPartialName(request.getFieldName());

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        PDAppearanceDictionary appearances = new PDAppearanceDictionary();

        PDAppearanceStream offAppearance = new PDAppearanceStream(doc);
        offAppearance.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));
        offAppearance.setResources(acroForm.getDefaultResources());
        try (PDPageContentStream cs = new PDPageContentStream(doc, offAppearance)) {
            cs.setStrokingColor(0f);
            cs.setLineWidth(1f);
            cs.addRect(0.5f, 0.5f, Math.max(1f, rect.getWidth() - 1f), Math.max(1f, rect.getHeight() - 1f));
            cs.stroke();
        }

        PDAppearanceStream onAppearance = new PDAppearanceStream(doc);
        onAppearance.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));
        onAppearance.setResources(acroForm.getDefaultResources());
        try (PDPageContentStream cs = new PDPageContentStream(doc, onAppearance)) {
            cs.setStrokingColor(0f);
            cs.setLineWidth(1f);
            cs.addRect(0.5f, 0.5f, Math.max(1f, rect.getWidth() - 1f), Math.max(1f, rect.getHeight() - 1f));
            cs.stroke();
            cs.setLineWidth(Math.max(2f, rect.getWidth() * 0.1f));
            float w = rect.getWidth();
            float h = rect.getHeight();
            float margin = Math.min(w, h) * 0.15f;
            cs.moveTo(margin, h * 0.45f);
            cs.lineTo(w * 0.4f, margin);
            cs.stroke();
            cs.moveTo(w * 0.4f, margin);
            cs.lineTo(w - margin, h - margin);
            cs.stroke();
        }

        COSDictionary normalAppDic = new COSDictionary();
        normalAppDic.setItem(COSName.getPDFName("Off"), offAppearance.getCOSObject());
        normalAppDic.setItem(COSName.getPDFName("Yes"), onAppearance.getCOSObject());
        appearances.getCOSObject().setItem(COSName.N, normalAppDic);
        widget.setAppearance(appearances);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
        optionApplier.applyOptionsToField(field, request.getOptions());
    }

    void addComboField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                       PDRectangle rect, FormFieldRequest request) throws IOException {
        PDComboBox field = new PDComboBox(acroForm);
        field.setPartialName(request.getFieldName());

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
        optionApplier.applyOptionsToField(field, request.getOptions());
    }

    void addListField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                      PDRectangle rect, FormFieldRequest request) throws IOException {
        PDListBox field = new PDListBox(acroForm);
        field.setPartialName(request.getFieldName());
        field.setDefaultAppearance("/Helv 10 Tf 0 g");

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
        optionApplier.applyOptionsToField(field, request.getOptions());
    }

    /**
     * Creates a radio button group with one widget per request entry.
     * All requests must share the same fieldName (group name).
     * Each request's options must contain "exportValue" identifying the option.
     */
    void addRadioGroup(PDDocument doc, PDAcroForm acroForm,
                       List<FormFieldRequest> requests) throws IOException {
        FormFieldRequest first = requests.get(0);

        // Resolve export values (fall back to "option1", "option2", … if blank)
        List<String> exportValues = new ArrayList<>();
        for (FormFieldRequest r : requests) {
            Object ev = r.getOptions() != null ? r.getOptions().get("exportValue") : null;
            String evStr = ev != null ? String.valueOf(ev).trim() : "";
            exportValues.add(evStr.isEmpty() ? "option" + (exportValues.size() + 1) : evStr);
        }

        PDRadioButton group = new PDRadioButton(acroForm);
        group.setPartialName(first.getFieldName());

        // Build Kids array — one widget dict per option
        COSArray kids = new COSArray();
        List<COSDictionary> widgetDicts = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            FormFieldRequest r = requests.get(i);
            PDRectangle bbox = new PDRectangle((float) r.getWidth(), (float) r.getHeight());

            // OFF appearance
            PDAppearanceStream offStream = new PDAppearanceStream(doc);
            offStream.setBBox(bbox);
            offStream.setResources(acroForm.getDefaultResources());

            // ON appearance (keyed by export value — viewer renders the selection mark)
            PDAppearanceStream onStream = new PDAppearanceStream(doc);
            onStream.setBBox(bbox);
            onStream.setResources(acroForm.getDefaultResources());

            COSDictionary nDict = new COSDictionary();
            nDict.setItem(COSName.getPDFName("Off"), offStream.getCOSObject());
            nDict.setItem(COSName.getPDFName(exportValues.get(i)), onStream.getCOSObject());

            COSDictionary apDict = new COSDictionary();
            apDict.setItem(COSName.N, nDict);

            COSDictionary wDict = new COSDictionary();
            wDict.setItem(COSName.TYPE, COSName.ANNOT);
            wDict.setItem(COSName.SUBTYPE, COSName.getPDFName("Widget"));
            wDict.setItem(COSName.PARENT, group.getCOSObject());
            wDict.setItem(COSName.AS, COSName.getPDFName("Off")); // start unchecked
            wDict.setItem(COSName.AP, apDict);

            kids.add(wDict);
            widgetDicts.add(wDict);
        }
        group.getCOSObject().setItem(COSName.KIDS, kids);

        // Position each widget and attach to its page
        for (int i = 0; i < requests.size(); i++) {
            FormFieldRequest r = requests.get(i);
            PDPage page = doc.getPage(r.getPageIndex());
            PDRectangle rect = new PDRectangle(
                    (float) r.getX(), (float) r.getY(), (float) r.getWidth(), (float) r.getHeight());
            PDAnnotationWidget widget = new PDAnnotationWidget(widgetDicts.get(i));
            widget.setRectangle(rect);
            widget.setPage(page);
            widget.setPrinted(true);
            page.getAnnotations().add(widget);
        }

        acroForm.getFields().add(group);

        // Apply shared options (required, readonly, javascript); promote radioDefault → defaultValue
        Map<String, Object> opts = new HashMap<>(first.getOptions() != null ? first.getOptions() : Map.of());
        Object rd = opts.remove("radioDefault");
        if (rd != null && !String.valueOf(rd).isBlank()) opts.put("defaultValue", rd);
        optionApplier.applyOptionsToField(group, opts);
    }

    void addSignatureField(PDDocument doc, PDAcroForm acroForm, PDPage page,
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
        optionApplier.applyOptionsToField(field, request.getOptions());
    }
}
