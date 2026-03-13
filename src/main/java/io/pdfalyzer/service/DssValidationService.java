package io.pdfalyzer.service;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Service;

import eu.europa.esig.dss.detailedreport.DetailedReportFacade;
import eu.europa.esig.dss.detailedreport.jaxb.XmlDetailedReport;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.simplereport.SimpleReportFacade;
import eu.europa.esig.dss.simplereport.jaxb.XmlSimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.validation.reports.Reports;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs EU DSS signature validation on a PDF document and produces
 * SimpleReport and DetailedReport in both XML and HTML formats.
 */
@Service
@Slf4j
public class DssValidationService {

    /**
     * Validate a PDF using the EU DSS framework.
     * Returns a map with: available, success, simpleReportXml, simpleReportHtml,
     * detailedReportXml, detailedReportHtml, and error (if any).
     */
    public Map<String, Object> validate(byte[] pdfBytes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);

        try {
            DSSDocument document = new InMemoryDocument(pdfBytes, "document.pdf");

            // Create validator for PAdES
            PDFDocumentValidator validator = new PDFDocumentValidator(document);

            // Configure certificate verifier with online revocation sources
            CommonCertificateVerifier verifier = new CommonCertificateVerifier();
            verifier.setOcspSource(new OnlineOCSPSource());
            verifier.setCrlSource(new OnlineCRLSource());
            validator.setCertificateVerifier(verifier);

            // Run validation
            Reports reports = validator.validateDocument();

            // SimpleReport: use pre-marshalled XML from Reports, generate HTML via facade
            String simpleReportXml = reports.getXmlSimpleReport();

            XmlSimpleReport simpleReportJaxb = reports.getSimpleReportJaxb();
            SimpleReportFacade simpleReportFacade = SimpleReportFacade.newFacade();
            StringWriter simpleHtmlWriter = new StringWriter();
            simpleReportFacade.generateHtmlReport(simpleReportJaxb, new StreamResult(simpleHtmlWriter));
            String simpleReportHtml = simpleHtmlWriter.toString();

            // DetailedReport: use pre-marshalled XML, generate HTML via facade
            String detailedReportXml = reports.getXmlDetailedReport();

            XmlDetailedReport detailedReportJaxb = reports.getDetailedReportJaxb();
            DetailedReportFacade detailedReportFacade = DetailedReportFacade.newFacade();
            StringWriter detailedHtmlWriter = new StringWriter();
            detailedReportFacade.generateHtmlReport(detailedReportJaxb, new StreamResult(detailedHtmlWriter));
            String detailedReportHtml = detailedHtmlWriter.toString();

            result.put("success", true);
            result.put("simpleReportXml", simpleReportXml);
            result.put("simpleReportHtml", simpleReportHtml);
            result.put("detailedReportXml", detailedReportXml);
            result.put("detailedReportHtml", detailedReportHtml);

            log.info("EU DSS validation completed successfully");

        } catch (Exception e) {
            log.error("EU DSS validation failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
}
