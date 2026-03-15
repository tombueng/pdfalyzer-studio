package io.pdfalyzer.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignatureAnalysisResult {
    private int totalSignatureFields;
    private int signedCount;
    private int unsignedCount;
    private int validCount;
    private int invalidCount;
    private int indeterminateCount;
    private boolean hasCertificationSignature;
    private boolean hasDss;
    @Builder.Default
    private List<SignatureInfo> signatures = new ArrayList<>();

    // PDF revision boundaries
    @Builder.Default
    private List<PdfRevision> revisions = new ArrayList<>();

    // Trust list status (populated by on-demand validation)
    private TrustListStatus eutlStatus;
    private TrustListStatus aatlStatus;
}
