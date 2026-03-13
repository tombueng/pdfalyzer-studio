package io.pdfalyzer.service;

import io.pdfalyzer.model.PdfNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

/**
 * Thin coordinator: delegates to {@link SemanticTreeBuilder} for the semantic view
 * and {@link CosNodeBuilder} for the raw COS view.
 * <p>
 * Kept as a separate Spring component so existing injection points are unchanged.
 */
@Component
public class PdfStructureParser {

    private final SemanticTreeBuilder semanticBuilder;
    private final CosNodeBuilder cosBuilder;

    public PdfStructureParser(SemanticTreeBuilder semanticBuilder, CosNodeBuilder cosBuilder) {
        this.semanticBuilder = semanticBuilder;
        this.cosBuilder = cosBuilder;
    }

    /** Build the high-level semantic tree for a PDF document. */
    public PdfNode buildTree(PDDocument doc) {
        return semanticBuilder.buildTree(doc);
    }

    /** Build the flat raw-COS tree (all indirect objects) on demand. */
    public PdfNode buildRawCosTree(PDDocument doc) {
        return cosBuilder.buildRawCosTree(doc);
    }
}
