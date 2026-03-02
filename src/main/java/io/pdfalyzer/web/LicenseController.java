package io.pdfalyzer.web;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LicenseController {

    private static final String LICENSE_ROOT = "/META-INF/resources/license/";
    private static final Pattern SKIP_EXTENSIONS = Pattern.compile(".*\\.(png|jpg|jpeg|gif|svg|ico)$", Pattern.CASE_INSENSITIVE);

    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    @GetMapping("/license")
    public String licenseOverview(Model model) throws IOException {
        Resource[] resources = resourceResolver.getResources("classpath*:/META-INF/resources/license/**/*");
        Set<String> docPaths = new LinkedHashSet<>();

        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) {
                continue;
            }
            String rawLocation = resource.getURL().toString().replace('\\', '/');
            int rootIndex = rawLocation.lastIndexOf(LICENSE_ROOT);
            if (rootIndex < 0) {
                continue;
            }
            String relativePath = rawLocation.substring(rootIndex + LICENSE_ROOT.length());
            if (relativePath.isBlank() || relativePath.endsWith("/")) {
                continue;
            }
            if (relativePath.contains("..") || SKIP_EXTENSIONS.matcher(relativePath).matches()) {
                continue;
            }
            docPaths.add(relativePath);
        }

        List<LicenseDoc> documents = new ArrayList<>();
        for (String relativePath : docPaths) {
            String displayPath = decodePathForDisplay(relativePath);
            documents.add(new LicenseDoc(
                displayPath,
                "/license/" + relativePath,
                categoryOf(displayPath),
                extensionOf(displayPath),
                fileNameOf(displayPath)
            ));
        }
        documents.sort(Comparator
            .comparing(LicenseDoc::category)
            .thenComparing(LicenseDoc::displayPath));

        Map<String, List<LicenseDoc>> groupedDocuments = new LinkedHashMap<>();
        for (LicenseDoc doc : documents) {
            groupedDocuments.computeIfAbsent(doc.category(), ignored -> new ArrayList<>()).add(doc);
        }

        model.addAttribute("documents", documents);
        model.addAttribute("groupedDocuments", groupedDocuments);
        return "license-overview";
    }

    private String categoryOf(String displayPath) {
        int slash = displayPath.indexOf('/');
        if (slash < 0) {
            return "root";
        }
        return displayPath.substring(0, slash);
    }

    private String extensionOf(String displayPath) {
        String fileName = displayPath;
        int slash = displayPath.lastIndexOf('/');
        if (slash >= 0 && slash < displayPath.length() - 1) {
            fileName = displayPath.substring(slash + 1);
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String fileNameOf(String displayPath) {
        int slash = displayPath.lastIndexOf('/');
        if (slash < 0) {
            return displayPath;
        }
        if (slash == displayPath.length() - 1) {
            return "";
        }
        return displayPath.substring(slash + 1);
    }

    private String decodePathForDisplay(String relativePath) {
        try {
            URI uri = URI.create("https://local/" + relativePath);
            return uri.getPath().substring(1);
        } catch (Exception ignored) {
            return relativePath;
        }
    }

    public record LicenseDoc(String displayPath, String href, String category, String extension, String downloadName) {}
}
