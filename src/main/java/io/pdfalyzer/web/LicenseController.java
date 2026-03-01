package io.pdfalyzer.web;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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
            documents.add(new LicenseDoc(decodePathForDisplay(relativePath), "/license/" + relativePath));
        }
        documents.sort(Comparator.comparing(LicenseDoc::displayPath));

        model.addAttribute("documents", documents);
        return "license-overview";
    }

    private String decodePathForDisplay(String relativePath) {
        try {
            URI uri = URI.create("https://local/" + relativePath);
            return uri.getPath().substring(1);
        } catch (Exception ignored) {
            return relativePath;
        }
    }

    public record LicenseDoc(String displayPath, String href) {}
}
