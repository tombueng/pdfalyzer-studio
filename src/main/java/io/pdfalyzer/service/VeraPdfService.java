package io.pdfalyzer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class VeraPdfService {

    private static final Logger log = LoggerFactory.getLogger(VeraPdfService.class);

    public Map<String, Object> validate(byte[] pdfBytes) throws IOException {
        Path tempPdf = Files.createTempFile("pdfalyzer-verapdf-", ".pdf");
        Files.write(tempPdf, pdfBytes);

        try {
            Process process = new ProcessBuilder("verapdf", "--format", "text", tempPdf.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            boolean finished;
            try {
                finished = process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("veraPDF execution interrupted", ex);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            if (!finished) {
                process.destroyForcibly();
                result.put("available", true);
                result.put("success", false);
                result.put("report", "veraPDF timed out after 30 seconds.");
                return result;
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.exitValue();

            result.put("available", true);
            result.put("success", exit == 0);
            result.put("exitCode", exit);
            result.put("report", output == null ? "" : output);
            return result;
        } catch (IOException cmdEx) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", false);
            result.put("success", false);
            result.put("report", "veraPDF CLI is not available. Install veraPDF and add it to PATH.");
            log.debug("veraPDF command not available", cmdEx);
            return result;
        } finally {
            try {
                Files.deleteIfExists(tempPdf);
            } catch (Exception ignored) {
            }
        }
    }
}
