package io.pdfalyzer.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FontLicenseAuditTest {

    private record LicensePolicy(
        String license,
        String source,
        boolean copyPublishAllowed,
        String conditions
    ) {}

    private static final Path STRANGE_WEB_FONT_DIR =
        Paths.get("src", "test", "resources", "fonts", "strange-web");

    private static final Map<String, LicensePolicy> EXPECTED_LICENSES = Map.ofEntries(
        Map.entry("AND-Regular.otf", new LicensePolicy(
            "SIL OFL 1.1",
            "adobe-fonts/adobe-notdef",
            true,
            "Can copy/publish if OFL text and notices are preserved; do not sell font by itself."
        )),
        Map.entry("fa-solid-900.ttf", new LicensePolicy(
            "SIL OFL 1.1 (Font Awesome Free fonts)",
            "FortAwesome/Font-Awesome",
            true,
            "Can copy/publish under OFL; attribution/comments should be kept when redistributing."
        )),
        Map.entry("Jersey15Charted-Regular.ttf", new LicensePolicy(
            "SIL OFL 1.1",
            "google/fonts",
            true,
            "Can copy/publish if OFL notice is kept; do not sell this font file by itself."
        )),
        Map.entry("MaterialIcons-Regular.ttf", new LicensePolicy(
            "Apache License 2.0",
            "google/material-design-icons",
            true,
            "Can copy/publish with Apache-2.0 notice and license retention requirements."
        )),
        Map.entry("NotoSansCuneiform-Regular.ttf", new LicensePolicy(
            "SIL OFL 1.1",
            "notofonts/noto-fonts",
            true,
            "Can copy/publish if OFL notice is kept; do not sell this font file by itself."
        )),
        Map.entry("NotoSansEgyptianHieroglyphs-Regular.ttf", new LicensePolicy(
            "SIL OFL 1.1",
            "notofonts/noto-fonts",
            true,
            "Can copy/publish if OFL notice is kept; do not sell this font file by itself."
        )),
        Map.entry("NotoSansLinearA-Regular.ttf", new LicensePolicy(
            "SIL OFL 1.1",
            "notofonts/noto-fonts",
            true,
            "Can copy/publish if OFL notice is kept; do not sell this font file by itself."
        )),
        Map.entry("NotoSansLinearB-Regular.ttf", new LicensePolicy(
            "SIL OFL 1.1",
            "notofonts/noto-fonts",
            true,
            "Can copy/publish if OFL notice is kept; do not sell this font file by itself."
        )),
        Map.entry("NotoSansOldSouthArabian-Regular.ttf", new LicensePolicy(
            "SIL OFL 1.1",
            "notofonts/noto-fonts",
            true,
            "Can copy/publish if OFL notice is kept; do not sell this font file by itself."
        )),
        Map.entry("NotoSansOldTurkic-Regular.ttf", new LicensePolicy(
            "SIL OFL 1.1",
            "notofonts/noto-fonts",
            true,
            "Can copy/publish if OFL notice is kept; do not sell this font file by itself."
        )),
        Map.entry("NotoSansSymbols2-Regular.ttf", new LicensePolicy(
            "SIL OFL 1.1",
            "notofonts/noto-fonts",
            true,
            "Can copy/publish if OFL notice is kept; do not sell this font file by itself."
        )),
        Map.entry("UnifrakturMaguntia-Book.ttf", new LicensePolicy(
            "SIL OFL 1.1",
            "google/fonts",
            true,
            "Can copy/publish if OFL notice is kept; do not sell this font file by itself."
        ))
    );

    @Test
    void printAndVerifyLicensePolicyForAllStrangeWebFonts() throws IOException {
        Set<String> discoveredFiles = listStrangeWebFontFiles();

        assertTrue(!discoveredFiles.isEmpty(), "No files found under " + STRANGE_WEB_FONT_DIR);

        List<String> unknownFiles = discoveredFiles.stream()
            .filter(name -> !EXPECTED_LICENSES.containsKey(name))
            .sorted()
            .toList();

        List<String> missingFiles = EXPECTED_LICENSES.keySet().stream()
            .filter(name -> !discoveredFiles.contains(name))
            .sorted()
            .toList();

        List<String> disallowedFiles = new ArrayList<>();
        List<String> strictReviewRequired = new ArrayList<>();

        System.out.println("\n=== Strange Web Font License Audit ===");
        System.out.println("Legal note: automated summary for testing; confirm final legal decisions with your counsel.");

        discoveredFiles.stream()
            .sorted()
            .forEach(name -> {
                LicensePolicy policy = EXPECTED_LICENSES.get(name);
                if (policy == null) {
                    System.out.printf("- %-40s | UNKNOWN | REVIEW REQUIRED%n", name);
                    return;
                }

                String verdict = policy.copyPublishAllowed ? "YES (with conditions)" : "NO / REVIEW";
                System.out.printf(
                    "- %-40s | %-30s | %s%n  source=%s%n  conditions=%s%n",
                    name,
                    policy.license,
                    verdict,
                    policy.source,
                    policy.conditions
                );

                if (policy.license().contains("AGPL")) {
                    strictReviewRequired.add(name);
                }

                if (!policy.copyPublishAllowed) {
                    disallowedFiles.add(name);
                }
            });

        assertTrue(
            unknownFiles.isEmpty(),
            "Add license policy entries for new files: " + unknownFiles
        );

        assertTrue(
            missingFiles.isEmpty(),
            "Expected files missing from test resources: " + missingFiles
        );

        assertTrue(
            disallowedFiles.isEmpty(),
            "These fonts are not approved for copy/publish by policy: " + disallowedFiles
        );

        assertTrue(
            strictReviewRequired.isEmpty(),
            "Strict commercial default requires zero AGPL/review-required fonts, found: " + strictReviewRequired
        );
    }

    @Test
    void strictCommercialRedistributionPolicy_requiresReviewForAgplFonts() throws IOException {
        Set<String> discoveredFiles = listStrangeWebFontFiles();
        assertTrue(!discoveredFiles.isEmpty(), "No files found under " + STRANGE_WEB_FONT_DIR);

        Set<String> reviewRequired = discoveredFiles.stream()
            .filter(EXPECTED_LICENSES::containsKey)
            .filter(name -> EXPECTED_LICENSES.get(name).license().contains("AGPL"))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> expectedReviewRequired = Set.of();

        System.out.println("\n=== Strict Commercial Redistribution Policy ===");
        discoveredFiles.stream().sorted().forEach(name -> {
            LicensePolicy policy = EXPECTED_LICENSES.get(name);
            if (policy == null) {
                System.out.printf("- %-40s | UNKNOWN | REVIEW REQUIRED%n", name);
                return;
            }
            boolean needsReview = reviewRequired.contains(name);
            String verdict = needsReview
                ? "REVIEW REQUIRED (commercial redistribution)"
                : "OK (commercial redistribution)";
            System.out.printf("- %-40s | %-30s | %s%n", name, policy.license(), verdict);
        });

        assertTrue(
            reviewRequired.equals(expectedReviewRequired),
            "Strict commercial review set changed. expected=" + expectedReviewRequired + " actual=" + reviewRequired
        );
    }

    private static Set<String> listStrangeWebFontFiles() throws IOException {
        if (!Files.exists(STRANGE_WEB_FONT_DIR)) {
            return Set.of();
        }

        try (Stream<Path> stream = Files.walk(STRANGE_WEB_FONT_DIR)) {
            return stream
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }
}
