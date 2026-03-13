///usr/bin/env java --enable-preview --source 21 "$0" "$@"; exit $?

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Extracts TikZ diagrams from AsciiDoc files, renders each to SVG.
 *
 * Usage: java scripts/TikzExtractor.java <chapters-dir> <output-dir> <preamble.tex>
 *
 * For each [latexmath] block containing \begin{tikzpicture},
 * this tool:
 *   1. Wraps it in a standalone LaTeX document
 *   2. Compiles with lualatex → PDF
 *   3. Converts PDF → SVG using pdf2svg
 *
 * Requires: lualatex, pdf2svg
 */
public class TikzExtractor {

    record TikzBlock(String sourceFile, int index, String tikzCode) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java TikzExtractor.java <chapters-dir> <output-dir> <preamble.tex>");
            System.exit(1);
        }

        Path chaptersDir = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        Path preambleFile = Path.of(args[2]);

        Files.createDirectories(outputDir);
        Path tmpDir = outputDir.resolve("_tmp");
        Files.createDirectories(tmpDir);

        String preambleContent = Files.readString(preambleFile);
        // Strip book-specific packages that conflict with standalone
        preambleContent = stripBookPackages(preambleContent);

        List<TikzBlock> blocks = new ArrayList<>();

        // Find all .adoc files recursively
        try (Stream<Path> paths = Files.walk(chaptersDir)) {
            List<Path> adocFiles = paths
                    .filter(p -> p.toString().endsWith(".adoc"))
                    .sorted()
                    .toList();

            for (Path adocFile : adocFiles) {
                blocks.addAll(extractTikzBlocks(adocFile));
            }
        }

        System.out.printf("Found %d TikZ diagrams across all chapters%n", blocks.size());

        int success = 0;
        for (TikzBlock block : blocks) {
            String basename = String.format("tikz-%s-%03d",
                    block.sourceFile.replaceAll("[^a-zA-Z0-9]", "-"), block.index);

            Path texFile = tmpDir.resolve(basename + ".tex");
            Path pdfFile = tmpDir.resolve(basename + ".pdf");
            Path svgFile = outputDir.resolve(basename + ".svg");

            // Write standalone LaTeX document
            String doc = buildStandaloneDoc(block.tikzCode, preambleContent);
            Files.writeString(texFile, doc);

            // Compile with lualatex
            ProcessBuilder pb = new ProcessBuilder(
                    "lualatex",
                    "-interaction=nonstopmode",
                    "-output-directory=" + tmpDir,
                    texFile.toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();

            if (exit != 0 || !Files.exists(pdfFile)) {
                System.err.printf("WARNING: lualatex failed for %s (exit %d)%n", basename, exit);
                // Print last 10 lines of output for debugging
                String[] lines = output.split("\n");
                for (int i = Math.max(0, lines.length - 10); i < lines.length; i++) {
                    System.err.println("  " + lines[i]);
                }
                continue;
            }

            // Convert PDF → SVG
            ProcessBuilder svgPb = new ProcessBuilder("pdf2svg", pdfFile.toString(), svgFile.toString());
            svgPb.inheritIO();
            int svgExit = svgPb.start().waitFor();

            if (svgExit == 0 && Files.exists(svgFile)) {
                System.out.printf("  Rendered: %s → %s%n", basename, svgFile.getFileName());
                success++;
            } else {
                System.err.printf("WARNING: pdf2svg failed for %s%n", basename);
            }
        }

        // Cleanup tmp
        // (keep it for debugging; use -Dclean=true to remove)
        if ("true".equals(System.getProperty("clean"))) {
            try (Stream<Path> tmpFiles = Files.walk(tmpDir)) {
                tmpFiles.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }

        System.out.printf("Successfully rendered %d/%d diagrams%n", success, blocks.size());
    }

    static List<TikzBlock> extractTikzBlocks(Path adocFile) throws IOException {
        List<TikzBlock> blocks = new ArrayList<>();
        List<String> lines = Files.readAllLines(adocFile);
        String filename = adocFile.getFileName().toString().replace(".adoc", "");

        int counter = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Look for [latexmath] followed by ++++
            if ("[latexmath]".equals(line) && i + 1 < lines.size() && "++++".equals(lines.get(i + 1).trim())) {
                int contentStart = i + 2;
                int end = -1;
                for (int j = contentStart; j < lines.size(); j++) {
                    if ("++++".equals(lines.get(j).trim())) {
                        end = j;
                        break;
                    }
                }
                if (end > contentStart) {
                    String content = String.join("\n", lines.subList(contentStart, end));
                    if (content.contains("\\begin{tikzpicture}")) {
                        counter++;
                        blocks.add(new TikzBlock(filename, counter, content));
                    }
                }
            }
        }
        return blocks;
    }

    static String buildStandaloneDoc(String tikzCode, String preambleContent) {
        return """
                \\documentclass[tikz,border=10pt]{standalone}
                \\usepackage{fontspec}
                \\usepackage{tikz}
                \\usetikzlibrary{
                    arrows.meta,positioning,calc,
                    decorations.pathreplacing,
                    shapes.geometric,shapes.multipart,
                    fit,backgrounds,chains,matrix
                }
                \\usepackage[edges]{forest}
                \\begin{document}
                %s
                \\end{document}
                """.formatted(tikzCode);
    }

    static String stripBookPackages(String preamble) {
        // Remove packages that conflict with standalone class
        return preamble
                .replaceAll("(?m)^.*\\\\usepackage\\[.*\\]\\{geometry\\}.*$", "")
                .replaceAll("(?m)^.*\\\\usepackage\\{fancyhdr\\}.*$", "")
                .replaceAll("(?m)^.*\\\\pagestyle\\{.*\\}.*$", "")
                .replaceAll("(?m)^.*\\\\usepackage\\{titlesec\\}.*$", "")
                .replaceAll("(?m)^.*\\\\titleformat.*$", "")
                .replaceAll("(?m)^.*\\\\titlespacing.*$", "")
                .replaceAll("(?m)^.*\\\\usepackage\\{makeidx\\}.*$", "")
                .replaceAll("(?m)^.*\\\\makeindex.*$", "")
                .replaceAll("(?m)^.*\\\\usepackage\\[.*\\]\\{biblatex\\}.*$", "");
    }
}
