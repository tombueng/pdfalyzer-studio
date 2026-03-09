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
public class PendingSignatureData {
    private String pendingSignatureId;
    private String fieldName;
    private String sessionKeyId;

    // Visual
    private String visualMode;
    private String displayName;
    private String fontName;
    private String imageDataBase64;
    private String drawnImageBase64;

    // Options
    private String signMode;
    private int docMdpLevel;
    private String padesProfile;
    @Builder.Default
    private List<String> excludedFields = new ArrayList<>();

    // Metadata
    private String reason;
    private String location;
    private String contactInfo;

    // Biometric data
    private String biometricData;
    private String biometricFormat; // "json", "json-zip", "binary"

    // TSA (Time Stamp Authority)
    private String tsaServerId;
    private String tsaUrl;

    // Tracking
    private long createdAt;
    private int orderIndex;

    // Certificate summary (for display in Changes tab, not the actual key)
    private String certSubjectDN;
    private String certIssuerDN;
}
