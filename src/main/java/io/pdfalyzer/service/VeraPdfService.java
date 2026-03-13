package io.pdfalyzer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.verapdf.features.FeatureFactory;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.metadata.fixer.FixerFactory;
import org.verapdf.pdfa.validation.validators.ValidatorConfig;
import org.verapdf.pdfa.validation.validators.ValidatorConfigBuilder;
import org.verapdf.processor.BatchProcessingHandler;
import org.verapdf.processor.BatchProcessor;
import org.verapdf.processor.FormatOption;
import org.verapdf.processor.ProcessorConfig;
import org.verapdf.processor.ProcessorFactory;
import org.verapdf.processor.TaskType;
import org.verapdf.processor.plugins.PluginsCollectionConfig;
import org.verapdf.processor.reports.BatchSummary;
import org.verapdf.processor.reports.ValidationBatchSummary;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class VeraPdfService {
    private Set<Thread> threadsBeforeInit;

    public Map<String, Object> validate(byte[] pdfBytes) throws IOException {
        Path tempPdf = Files.createTempFile("pdfalyzer-verapdf-", ".pdf");
        Files.write(tempPdf, pdfBytes);

        try {
            threadsBeforeInit = Thread.getAllStackTraces().keySet();
            VeraGreenfieldFoundryProvider.initialise();

            ValidatorConfig validatorConfig = ValidatorConfigBuilder.defaultBuilder()
                    .recordPasses(true)
                    .maxFails(-1)
                    .maxNumberOfDisplayedFailedChecks(Integer.MAX_VALUE)
                    .build();

            ProcessorConfig config = ProcessorFactory.fromValues(
                    validatorConfig,
                    FeatureFactory.defaultConfig(),
                    PluginsCollectionConfig.defaultConfig(),
                    FixerFactory.defaultConfig(),
                    EnumSet.of(TaskType.VALIDATE));
            BatchProcessor processor = ProcessorFactory.fileBatchProcessor(config);
            BatchSummary summary;
            String output;
            String reportFormat;

            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                BatchProcessingHandler handler = ProcessorFactory.getHandler(FormatOption.MRR, true, out, true);
                summary = processor.process(Collections.singletonList(tempPdf.toFile()), handler);
                output = out.toString(StandardCharsets.UTF_8);
                reportFormat = "xml";
            } catch (Exception mrrEx) {
                log.warn("veraPDF detailed XML report generation failed, retrying with HTML", mrrEx);
                try {
                    ByteArrayOutputStream htmlOut = new ByteArrayOutputStream();
                    BatchProcessingHandler htmlHandler = ProcessorFactory.getHandler(FormatOption.HTML, true, htmlOut,
                            true);
                    summary = processor.process(Collections.singletonList(tempPdf.toFile()), htmlHandler);
                    output = htmlOut.toString(StandardCharsets.UTF_8);
                    reportFormat = "html";
                } catch (Exception htmlEx) {
                    log.warn("veraPDF HTML report generation failed, retrying with text report", htmlEx);
                    ByteArrayOutputStream fallbackOut = new ByteArrayOutputStream();
                    BatchProcessingHandler fallbackHandler = ProcessorFactory.getHandler(FormatOption.TEXT, true,
                            fallbackOut, true);
                    summary = processor.process(Collections.singletonList(tempPdf.toFile()), fallbackHandler);
                    output = fallbackOut.toString(StandardCharsets.UTF_8);
                    reportFormat = "text";
                }
            }

            ValidationBatchSummary validationSummary = summary == null ? null : summary.getValidationSummary();
            int compliant = validationSummary == null ? 0 : validationSummary.getCompliantPdfaCount();
            int nonCompliant = validationSummary == null ? 0 : validationSummary.getNonCompliantPdfaCount();
            int failedParsing = summary == null ? 0 : summary.getFailedParsingJobs();
            int exceptions = summary == null ? 0 : summary.getVeraExceptions();
            int outOfMemory = summary == null ? 0 : summary.getOutOfMemory();

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
            cleanupVeraPdfThreads();
            try {
                Files.deleteIfExists(tempPdf);
            } catch (Exception ignored) {
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        cleanupVeraPdfThreads();
    }

    private void cleanupVeraPdfThreads() {
        if (threadsBeforeInit == null) {
            return;
        }
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!threadsBeforeInit.contains(t) && t != Thread.currentThread() && !t.isDaemon()) {
                log.debug("Interrupting lingering veraPDF thread: {}", t.getName());
                t.interrupt();
            }
        }
    }
}
