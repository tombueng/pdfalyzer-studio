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
public class SigningRequest {
    private String fieldName;
    private String sessionKeyId;

    // Visual representation
    @Builder.Default
    private String visualMode = "invisible"; // "text", "image", "draw", "invisible"
    private String displayName;
    private String fontName;
    private String imageDataBase64;
    private String drawnImageBase64;

    // Signing options
    @Builder.Default
    private String signMode = "approval"; // "approval", "certification"
    @Builder.Default
    private int docMdpLevel = 2; // 1=no changes, 2=form fill+sign, 3=form+annot+sign
    @Builder.Default
    private String padesProfile = "B-B"; // "B-B", "B-T", "B-LT", "B-LTA"
    @Builder.Default
    private List<String> excludedFields = new ArrayList<>();

    // Metadata
    private String reason;
    private String location;
    private String contactInfo;

    // Biometric data (JSON string of stroke data from the drawing canvas)
    private String biometricData;
    @Builder.Default
    private String biometricFormat = "json-zip"; // "json", "json-zip", "binary"

    // TSA (Time Stamp Authority)
    private String tsaServerId;
    private String tsaUrl;

    // Password (transient — not stored, only used at signing time)
    private String keyPassword;
}
