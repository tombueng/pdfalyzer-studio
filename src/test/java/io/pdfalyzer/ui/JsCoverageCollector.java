package io.pdfalyzer.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.chrome.ChromeDriver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures V8 precise coverage from a ChromeDriver session via the Chrome
 * DevTools Protocol. Output (one JSON per test) is written to
 * {@code target/js-coverage/raw/} and later post-processed in CI by
 * {@code tools/jscov-report.cjs} (monocart-coverage-reports) into LCOV.
 *
 * <p>Activated when system property {@code js.coverage} is {@code true}.
 * No-op otherwise so local Selenium runs stay fast.
 */
@Slf4j
public final class JsCoverageCollector {

    public static final String SYSTEM_PROPERTY = "js.coverage";
    private static final Path OUTPUT_DIR = Paths.get("target/js-coverage/raw");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final ChromeDriver driver;
    private final String label;

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(SYSTEM_PROPERTY, "false"));
    }

    public JsCoverageCollector(ChromeDriver driver, String label) {
        this.driver = driver;
        this.label = label;
    }

    public void start() {
        if (!isEnabled() || driver == null) {
            return;
        }
        try {
            driver.executeCdpCommand("Profiler.enable", Map.of());
            Map<String, Object> args = new HashMap<>();
            args.put("callCount", true);
            args.put("detailed", true);
            args.put("allowTriggeredUpdates", false);
            driver.executeCdpCommand("Profiler.startPreciseCoverage", args);
        } catch (Exception e) {
            log.warn("Failed to start V8 coverage capture for {}: {}", label, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void stopAndDump() {
        if (!isEnabled() || driver == null) {
            return;
        }
        try {
            Map<String, Object> result = driver.executeCdpCommand("Profiler.takePreciseCoverage", Map.of());
            driver.executeCdpCommand("Profiler.stopPreciseCoverage", Map.of());

            List<Map<String, Object>> rawResult = (List<Map<String, Object>>) result.get("result");
            if (rawResult == null || rawResult.isEmpty()) {
                return;
            }

            Files.createDirectories(OUTPUT_DIR);
            String safe = label.replaceAll("[^a-zA-Z0-9_.-]", "_");
            Path out = OUTPUT_DIR.resolve("coverage-%s-%d.json".formatted(safe, COUNTER.incrementAndGet()));
            Map<String, Object> wrapped = Map.of("result", rawResult);
            Files.writeString(out, JSON.writeValueAsString(wrapped));
            log.info("Wrote V8 coverage ({} scripts) for {} -> {}", rawResult.size(), label, out);
        } catch (Exception e) {
            log.warn("Failed to dump V8 coverage for {}: {}", label, e.getMessage());
        }
    }
}
