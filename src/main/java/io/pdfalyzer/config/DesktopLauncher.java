package io.pdfalyzer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens the bundled Chromium in app/kiosk mode once the web server is ready, and shuts the
 * application down again when that window is closed. This turns the packaged Windows installer
 * into a single, self-contained desktop launcher ({@code PdfalyzerStudio.exe}) — replacing the
 * previous {@code pdfalyzer.vbs}/{@code pdfalyzer.bat} helper scripts.
 *
 * <p>Disabled by default; the installer's EXE enables it via
 * {@code --pdfalyzer.desktop.launch-browser=true}, so normal {@code mvn spring-boot:run} and the
 * hosted/server deployments are unaffected.
 */
@Slf4j
@Component
public class DesktopLauncher {

    @Value("${pdfalyzer.desktop.launch-browser:false}")
    private boolean launchBrowser;

    @Value("${pdfalyzer.desktop.chromium-path:}")
    private String chromiumPathOverride;

    @Value("${pdfalyzer.desktop.profile-name:PDFalyzer Studio}")
    private String profileName;

    @Value("${pdfalyzer.desktop.shutdown-on-close:true}")
    private boolean shutdownOnClose;

    @Value("${server.port:8080}")
    private int serverPort;

    private final ApplicationContext applicationContext;

    public DesktopLauncher(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!launchBrowser) {
            log.debug("Desktop browser launch disabled (pdfalyzer.desktop.launch-browser=false).");
            return;
        }
        try {
            Path chromium = resolveChromium();
            if (chromium == null) {
                log.warn("Desktop launch requested but no Chromium binary was found; "
                        + "leaving the server running for manual access at http://localhost:{}.", serverPort);
                return;
            }
            Path profileDir = resolveProfileDir();
            prepareProfile(profileDir);
            Process chromeProcess = launchChromium(chromium, profileDir);
            log.info("Launched bundled Chromium (pid={}) in app mode at http://localhost:{} using profile {}.",
                    chromeProcess.pid(), serverPort, profileDir);
            if (shutdownOnClose) {
                startShutdownWatcher(chromeProcess, chromium, profileDir);
            }
        } catch (Exception e) {
            log.error("Failed to launch bundled Chromium; the server is still reachable at http://localhost:{}.",
                    serverPort, e);
        }
    }

    /** Resolves the Chromium executable from the configured override, the working directory, or the jar location. */
    private Path resolveChromium() {
        if (chromiumPathOverride != null && !chromiumPathOverride.isBlank()) {
            Path configured = Paths.get(chromiumPathOverride);
            if (Files.exists(configured)) {
                return configured;
            }
            log.warn("Configured chromium-path '{}' does not exist; falling back to auto-detection.", chromiumPathOverride);
        }
        // Install layout: <root>/chromium/chrome.exe, with the EXE/working dir at <root>.
        Path fromWorkingDir = Paths.get(System.getProperty("user.dir"), "chromium", "chrome.exe");
        if (Files.exists(fromWorkingDir)) {
            return fromWorkingDir;
        }
        // Fallback: <root>/app/pdfalyzer-studio.jar -> <root>/chromium/chrome.exe
        Path jarDir = locateJarDir();
        if (jarDir != null && jarDir.getParent() != null) {
            Path fromJar = jarDir.getParent().resolve("chromium").resolve("chrome.exe");
            if (Files.exists(fromJar)) {
                return fromJar;
            }
        }
        return null;
    }

    private Path locateJarDir() {
        try {
            Path self = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(self) ? self : self.getParent();
        } catch (Exception e) {
            log.debug("Could not resolve jar location: {}", e.toString());
            return null;
        }
    }

    /** Dedicated Chromium profile under %LOCALAPPDATA% (writable, separate from the user's own browser). */
    private Path resolveProfileDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base;
        if (localAppData != null && !localAppData.isBlank()) {
            base = Paths.get(localAppData, profileName);
        } else {
            base = Paths.get(System.getProperty("user.home"), "." + profileName.replace(' ', '-').toLowerCase());
        }
        return base.resolve("chromium-profile");
    }

    /** Writes first-run preferences so Chromium skips its setup nags (mirrors the old VBScript launcher). */
    private void prepareProfile(Path profileDir) throws Exception {
        Path defaultDir = profileDir.resolve("Default");
        Files.createDirectories(defaultDir);

        Path preferences = defaultDir.resolve("Preferences");
        if (Files.notExists(preferences)) {
            String prefs = "{"
                    + "\"browser\":{\"check_default_browser\":false,\"should_reset_check_default_browser\":false},"
                    + "\"translate\":{\"enabled\":false},"
                    + "\"translate_blocked_languages\":[\"de\",\"en\",\"fr\",\"es\",\"it\",\"pt\",\"zh\",\"ja\",\"ko\",\"ru\"],"
                    + "\"intl\":{\"accept_languages\":\"en-US,en\"},"
                    + "\"profile\":{\"default_content_setting_values\":{\"notifications\":2}},"
                    + "\"download\":{\"prompt_for_download\":false},"
                    + "\"session\":{\"restore_on_startup\":1}"
                    + "}";
            Files.writeString(preferences, prefs, StandardCharsets.UTF_8);
        }

        Path firstRun = profileDir.resolve("First Run");
        if (Files.notExists(firstRun)) {
            Files.createFile(firstRun);
        }
    }

    private Process launchChromium(Path chromium, Path profileDir) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(chromium.toString());
        // ?desktop=1 tells the web UI it is running as a native desktop window (disables right-click etc.)
        command.add("--app=http://localhost:" + serverPort + "/?desktop=1");
        command.add("--user-data-dir=" + profileDir);
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-extensions");
        command.add("--disable-background-networking");
        command.add("--disable-sync");
        command.add("--disable-translate");
        command.add("--disable-features=TranslateUI,Translate,OverscrollHistoryNavigation,InfiniteSessionRestore,MediaRouter");
        command.add("--disable-infobars");
        command.add("--disable-component-update");
        command.add("--lang=en-US");
        command.add("--autoplay-policy=no-user-gesture-required");
        command.add("--window-size=1280,900");

        ProcessBuilder builder = new ProcessBuilder(command);
        // Suppress the "Google API keys are missing. Some functionality of Chromium will be disabled."
        // infobar shown by Chrome for Testing / Chromium builds that ship without baked-in Google keys.
        // Providing any non-empty value makes Chromium treat the keys as configured and hides the warning.
        builder.environment().put("GOOGLE_API_KEY", "no");
        builder.environment().put("GOOGLE_DEFAULT_CLIENT_ID", "no");
        builder.environment().put("GOOGLE_DEFAULT_CLIENT_SECRET", "no");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    /**
     * Waits for the Chromium window to close, then shuts the application down. The initial process may
     * exit early if Chromium delegates to a relaunched instance, so we additionally poll for any
     * chrome process still using our dedicated profile directory before terminating.
     */
    private void startShutdownWatcher(Process chromeProcess, Path chromium, Path profileDir) {
        Thread watcher = new Thread(() -> {
            try {
                chromeProcess.waitFor();
                // Give a possible relaunched Chromium process time to appear, then poll until none remain.
                Thread.sleep(2500);
                while (isChromiumRunningWithProfile(chromium, profileDir)) {
                    Thread.sleep(1500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.info("Chromium window closed; shutting down PDFalyzer Studio.");
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }, "chromium-shutdown-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private boolean isChromiumRunningWithProfile(Path chromium, Path profileDir) {
        String profileKey = profileDir.toString().toLowerCase();
        String chromiumKey = chromium.toString().toLowerCase();
        return ProcessHandle.allProcesses().anyMatch(handle -> {
            ProcessHandle.Info info = handle.info();
            String command = info.command().map(String::toLowerCase).orElse("");
            String commandLine = info.commandLine().map(String::toLowerCase).orElse("");
            boolean isChromium = command.equals(chromiumKey)
                    || command.endsWith("chrome.exe")
                    || commandLine.contains("chrome.exe");
            return isChromium && commandLine.contains(profileKey);
        });
    }
}
