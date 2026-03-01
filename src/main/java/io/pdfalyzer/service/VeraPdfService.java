package io.pdfalyzer.service;

import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.processor.BatchProcessingHandler;
import org.verapdf.processor.BatchProcessor;
import org.verapdf.processor.FormatOption;
import org.verapdf.processor.ProcessorConfig;
import org.verapdf.processor.ProcessorFactory;
import org.verapdf.processor.reports.BatchSummary;
import org.verapdf.processor.reports.ValidationBatchSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class VeraPdfService {

    private static final Logger log = LoggerFactory.getLogger(VeraPdfService.class);

    public Map<String, Object> validate(byte[] pdfBytes) throws IOException {
        Path tempPdf = Files.createTempFile("pdfalyzer-verapdf-", ".pdf");
        Files.write(tempPdf, pdfBytes);

        try {
            VeraGreenfieldFoundryProvider.initialise();

            ProcessorConfig config = ProcessorFactory.defaultConfig();
            BatchProcessor processor = ProcessorFactory.fileBatchProcessor(config);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
                BatchProcessingHandler handler =
                    ProcessorFactory.getHandler(FormatOption.HTML, true, out, false);
            BatchSummary summary = processor.process(Collections.singletonList(tempPdf.toFile()), handler);

            ValidationBatchSummary validationSummary = summary == null ? null : summary.getValidationSummary();
            int compliant = validationSummary == null ? 0 : validationSummary.getCompliantPdfaCount();
            int nonCompliant = validationSummary == null ? 0 : validationSummary.getNonCompliantPdfaCount();
            int failedParsing = summary == null ? 0 : summary.getFailedParsingJobs();
            int exceptions = summary == null ? 0 : summary.getVeraExceptions();
            int outOfMemory = summary == null ? 0 : summary.getOutOfMemory();

            String output = out.toString(StandardCharsets.UTF_8);
            String reportFormat = "html";
            if (output == null || output.isBlank()) {
                reportFormat = "text";
                output = "veraPDF embedded validation finished. " +
                        "Jobs=" + (summary == null ? 0 : summary.getTotalJobs()) +
                        ", compliant=" + compliant +
                        ", nonCompliant=" + nonCompliant +
                        ", failedParsing=" + failedParsing +
                        ", exceptions=" + exceptions +
                        ", outOfMemory=" + outOfMemory + ".";
            }

            boolean success = failedParsing == 0 && exceptions == 0 && outOfMemory == 0 && nonCompliant == 0;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", true);
            result.put("success", success);
            result.put("compliant", compliant);
            result.put("nonCompliant", nonCompliant);
            result.put("failedParsing", failedParsing);
            result.put("exceptions", exceptions);
            result.put("report", output == null ? "" : output);
            result.put("reportFormat", reportFormat);
            return result;
        } catch (Exception ex) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", false);
            result.put("success", false);
            result.put("report", "Embedded veraPDF validation failed: " + ex.getMessage());
            result.put("reportFormat", "text");
            log.warn("Embedded veraPDF validation failed", ex);
            return result;
        } finally {
            try {
                Files.deleteIfExists(tempPdf);
            } catch (Exception ignored) {
            }
        }
    }
}
