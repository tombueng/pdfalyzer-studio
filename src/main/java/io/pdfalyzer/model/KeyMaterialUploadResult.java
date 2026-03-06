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
public class KeyMaterialUploadResult {
    private String sessionKeyId;
    private boolean hasPrivateKey;
    private boolean hasCertificate;
    private boolean hasCertChain;
    private int chainLength;

    // Certificate details (null if no cert)
    private String subjectDN;
    private String issuerDN;
    private String serialNumber;
    private String notBefore;
    private String notAfter;
    private String keyAlgorithm;
    private int keySize;

    @Builder.Default
    private List<String> missingElements = new ArrayList<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    private boolean readyToSign;
}
