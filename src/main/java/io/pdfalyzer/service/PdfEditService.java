package io.pdfalyzer.service;

import io.pdfalyzer.model.FormFieldRequest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class PdfEditService {

    private static final Logger log = LoggerFactory.getLogger(PdfEditService.class);

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

        String defaultValue = request.getOptions() != null ? request.getOptions().get("defaultValue") : null;
        if (defaultValue != null) {
            field.setValue(defaultValue);
        }
    }

    private void addCheckboxField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                                   PDRectangle rect, FormFieldRequest request) throws IOException {
        PDCheckBox field = new PDCheckBox(acroForm);
        field.setPartialName(request.getFieldName());

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        // Create appearance
        PDAppearanceDictionary appearances = new PDAppearanceDictionary();
        PDAppearanceStream normalAppearance = new PDAppearanceStream(doc);
        normalAppearance.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));
        appearances.setNormalAppearance(normalAppearance);
        widget.setAppearance(appearances);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
    }

    private void addComboField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                                PDRectangle rect, FormFieldRequest request) throws IOException {
        PDComboBox field = new PDComboBox(acroForm);
        field.setPartialName(request.getFieldName());

        if (request.getOptions() != null && request.getOptions().containsKey("choices")) {
            String[] choices = request.getOptions().get("choices").split(",");
            java.util.List<String> choiceList = java.util.Arrays.asList(choices);
            field.setOptions(choiceList);
        }

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
    }

    private void addRadioField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                                PDRectangle rect, FormFieldRequest request) throws IOException {
        PDRadioButton field = new PDRadioButton(acroForm);
        field.setPartialName(request.getFieldName());

        PDAnnotationWidget widget = new PDAnnotationWidget();
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        PDAppearanceDictionary appearances = new PDAppearanceDictionary();
        PDAppearanceStream normalAppearance = new PDAppearanceStream(doc);
        normalAppearance.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));
        appearances.setNormalAppearance(normalAppearance);
        widget.setAppearance(appearances);

        field.setWidgets(java.util.Collections.singletonList(widget));
        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
    }

    private void addSignatureField(PDDocument doc, PDAcroForm acroForm, PDPage page,
                                    PDRectangle rect, FormFieldRequest request) throws IOException {
        PDSignatureField field = new PDSignatureField(acroForm);
        field.setPartialName(request.getFieldName());

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(rect);
        widget.setPage(page);
        widget.setPrinted(true);

        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
    }
}
