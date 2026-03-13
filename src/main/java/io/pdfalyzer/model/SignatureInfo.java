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
public class SignatureInfo {
    private String fieldName;
    private String fullyQualifiedName;
    private boolean signed;

    // Signer info (null for unsigned)
    private String subjectDN;
    private String issuerDN;
    private String serialNumber;
    private String notBefore;
    private String notAfter;
    private String signingTime;
    private String signatureAlgorithm;
    private String digestAlgorithm;
    private String subFilter;

    // Signature classification
    private String signatureType; // "certification", "approval", "unsigned"
    private int docMdpPermissions; // 0=n/a, 1=no changes, 2=form fill, 3=form+annot

    // Byte range
    @Builder.Default
    private List<ByteRangeSegment> byteRange = new ArrayList<>();
    private long totalFileSize;
    @Builder.Default
    private List<CoverageGap> coverageGaps = new ArrayList<>();
    private boolean coversEntireFile;

    // Lock / permissions
    private LockInfo lockInfo;

    // Validation
    private String validationStatus; // "VALID", "INDETERMINATE", "INVALID", "NOT_VALIDATED"
    private String validationMessage;
    @Builder.Default
    private List<String> validationWarnings = new ArrayList<>();
    @Builder.Default
    private List<String> validationErrors = new ArrayList<>();
    private String dssDetailedReport;

    // Post-signature modification detection
    @Builder.Default
    private List<ModificationWarning> modifications = new ArrayList<>();

    // Visual appearance
    private int pageIndex; // -1 if no widget
    private double[] boundingBox; // [x, y, width, height]
    private boolean hasAppearance;

    // Certificate chain (full chain extracted from CMS)
    @Builder.Default
    private List<CertificateChainEntry> certificateChain = new ArrayList<>();

    // Revisions covered by this signature's byte range
    @Builder.Default
    private List<PdfRevision> coveredRevisions = new ArrayList<>();

    // Trust validation report (populated by on-demand validation)
    private TrustValidationReport trustValidation;

    // Signer name from signature dictionary (fallback when cert not parseable)
    private String signerName;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ByteRangeSegment {
        private long offset;
        private long length;
        private String label;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoverageGap {
        private long offset;
        private long length;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LockInfo {
        private String action;
        @Builder.Default
        private List<String> fields = new ArrayList<>();
        private int permissions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModificationWarning {
        private String severity; // "WARNING", "DANGER"
        private String description;
        private String detail;
    }
}
